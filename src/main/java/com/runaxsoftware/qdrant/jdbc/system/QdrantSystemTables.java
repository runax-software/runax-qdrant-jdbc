package com.runaxsoftware.qdrant.jdbc.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reads the system tables from Qdrant's REST API, always afresh: they describe state that
 * changes without the driver, so nothing here is kept between statements.
 *
 * <p>{@link SystemTable#COLLECTIONS}, {@link SystemTable#SNAPSHOTS},
 * {@link SystemTable#SHARD_KEYS}, and {@link SystemTable#SHARDS} list the collections and then
 * read each one, because Qdrant describes a collection only one at a time. That is one call per
 * collection, so those tables cost more than the others; a collection deleted between the
 * listing and its read is left out rather than failing the statement.
 *
 * <p>Every table's rows are sorted by the driver, by name or id, so what a statement returns
 * doesn't depend on the order Qdrant happens to answer in.
 */
public final class QdrantSystemTables implements SystemTableReader {

    private static final String MISSING_COLLECTION = "42S02";

    private final QdrantGateway gateway;

    /**
     * @param gateway where the rows are read from
     */
    public QdrantSystemTables(QdrantGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public List<Object[]> read(SystemTable table) throws SQLException {
        return switch (table) {
            case COLLECTIONS -> collections();
            case ALIASES -> aliases();
            case SNAPSHOTS -> snapshots();
            case SHARD_KEYS -> shardKeys();
            case SHARDS -> shards();
            case CLUSTER -> cluster();
            case PEERS -> peers();
            case TELEMETRY -> telemetry();
            case METRICS -> metrics();
            case ISSUES -> issues();
        };
    }

    // One row per collection, by name, so a statement's rows don't depend on Qdrant's order
    private List<Object[]> collections() throws SQLException {
        List<Object[]> rows = new ArrayList<>();

        for (String name : collectionNames()) {
            JsonNode info = skipMissing(() -> gateway.collectionInfo(name));
            if (info != null) rows.add(collectionRow(name, info));
        }

        return rows;
    }

    private List<Object[]> aliases() throws SQLException {
        List<QdrantAlias> aliases = new ArrayList<>(gateway.listAliases());
        aliases.sort(Comparator.comparing(QdrantAlias::name));

        List<Object[]> rows = new ArrayList<>(aliases.size());
        for (QdrantAlias alias : aliases) {
            rows.add(new Object[]{alias.name(), alias.collection()});
        }

        return rows;
    }

    /**
     * The whole storage's snapshots and then each collection's, both as Qdrant's
     * {@code SnapshotDescription}, with the whole storage's first.
     */
    private List<Object[]> snapshots() throws SQLException {
        List<Object[]> rows = new ArrayList<>();

        for (JsonNode snapshot : gateway.request(HttpMethod.GET, "/snapshots", null)) {
            rows.add(snapshotRow(null, snapshot));
        }

        for (String name : collectionNames()) {
            JsonNode snapshots = perCollection(name, "/snapshots");
            if (snapshots == null) continue;

            for (JsonNode snapshot : snapshots) rows.add(snapshotRow(name, snapshot));
        }

        rows.sort(byText(0).thenComparing(byText(1)));

        return rows;
    }

    /**
     * Every collection's shard keys, from Qdrant's own listing of them. A collection that isn't
     * custom-sharded has none, and Qdrant answers without the list at all rather than with an
     * empty one.
     */
    private List<Object[]> shardKeys() throws SQLException {
        List<Object[]> rows = new ArrayList<>();

        for (String name : collectionNames()) {
            JsonNode keys = perCollection(name, "/shards");
            if (keys == null) continue;

            for (JsonNode key : keys.path("shard_keys")) {
                rows.add(new Object[]{name, text(key.path("key"))});
            }
        }

        rows.sort(byText(0).thenComparing(byText(1)));

        return rows;
    }

    /**
     * Every shard of every collection, one row per copy: a local copy's peer is the peer the
     * driver is connected to, which Qdrant names in the same answer.
     */
    private List<Object[]> shards() throws SQLException {
        List<Object[]> rows = new ArrayList<>();

        for (String name : collectionNames()) {
            JsonNode info = perCollection(name, "/cluster");
            if (info == null) continue;

            Long peer = number(info.path("peer_id"));
            for (JsonNode shard : info.path("local_shards")) {
                rows.add(shardRow(name, shard, "local", peer));
            }

            for (JsonNode shard : info.path("remote_shards")) {
                rows.add(shardRow(name, shard, "remote", number(shard.path("peer_id"))));
            }
        }

        rows.sort(byText(0).thenComparing(byNumber(1)).thenComparing(byText(3))
            .thenComparing(byNumber(4)));

        return rows;
    }

    /**
     * The cluster as one row. Qdrant answers a standalone server with nothing but a status of
     * {@code disabled}, which leaves every other column null.
     */
    private List<Object[]> cluster() throws SQLException {
        JsonNode info = gateway.request(HttpMethod.GET, "/cluster", null);
        JsonNode raft = info.path("raft_info");
        JsonNode consensus = info.path("consensus_thread_status");
        JsonNode peers = info.path("peers");

        return List.<Object[]>of(new Object[]{
            text(info.path("status")),
            number(info.path("peer_id")),
            peers.isObject() ? Long.valueOf(peers.size()) : null,
            number(raft.path("term")),
            number(raft.path("commit")),
            number(raft.path("pending_operations")),
            number(raft.path("leader")),
            text(raft.path("role")),
            flag(raft.path("is_voter")),
            text(consensus.path("consensus_thread_status")),
            text(consensus.path("last_update")),
            text(consensus.path("err")),
            json(info.path("message_send_failures")),
        });
    }

    /**
     * Every peer, by id. Qdrant keys them by id and says which one answered, so a row knows
     * whether it is the peer the driver is connected to.
     */
    private List<Object[]> peers() throws SQLException {
        JsonNode info = gateway.request(HttpMethod.GET, "/cluster", null);
        Long self = number(info.path("peer_id"));
        Long leader = number(info.path("raft_info").path("leader"));
        List<Object[]> rows = new ArrayList<>();

        for (Map.Entry<String, JsonNode> peer : info.path("peers").properties()) {
            Long id = id(peer.getKey());

            rows.add(new Object[]{
                id,
                text(peer.getValue().path("uri")),
                id == null ? null : id.equals(self),
                id == null || leader == null ? null : id.equals(leader),
            });
        }

        rows.sort(byNumber(0));

        return rows;
    }

    /**
     * Qdrant's telemetry flattened to one row per value in it, by path. Its tree is deep and
     * its shape depends on the Qdrant version, so there is no column per field to have: a row
     * names where its value sits instead, as dotted keys with {@code [n]} for a list's places.
     *
     * <p>This reads the telemetry Qdrant answers with by default. Its deeper levels, which
     * {@code ?details_level=} asks for, are many times larger and aren't exposed here.
     */
    private List<Object[]> telemetry() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        flatten(gateway.request(HttpMethod.GET, "/telemetry", null), "", rows);
        rows.sort(byText(0));

        return rows;
    }

    /**
     * Qdrant's Prometheus metrics, one row per sample, by name and labels. A sample's labels
     * stay one JSON column: which labels a metric carries is the metric's own business, and
     * Qdrant's metrics don't agree on them.
     */
    private List<Object[]> metrics() throws SQLException {
        List<PrometheusText.Sample> samples = PrometheusText.parse(gateway.requestText("/metrics"));
        List<Object[]> rows = new ArrayList<>(samples.size());

        for (PrometheusText.Sample sample : samples) {
            rows.add(new Object[]{sample.name(), sample.labels(), sample.value(), sample.type(),
                sample.help()});
        }

        rows.sort(byText(0).thenComparing(byText(1)));

        return rows;
    }

    private List<Object[]> issues() throws SQLException {
        List<Object[]> rows = new ArrayList<>();

        for (JsonNode issue : gateway.request(HttpMethod.GET, "/issues", null).path("issues")) {
            rows.add(new Object[]{
                text(issue.path("id")),
                text(issue.path("description")),
                text(issue.path("related_collection")),
                text(issue.path("timestamp")),
                json(issue.path("solution")),
            });
        }

        rows.sort(byText(0));

        return rows;
    }

    // Rows

    private static Object[] collectionRow(String name, JsonNode info) {
        JsonNode params = info.path("config").path("params");

        return new Object[]{
            name,
            text(info.path("status")),
            text(info.path("optimizer_status")),
            number(info.path("points_count")),
            number(info.path("indexed_vectors_count")),
            number(info.path("segments_count")),
            number(params.path("shard_number")),
            number(params.path("replication_factor")),
            number(params.path("write_consistency_factor")),
            flag(params.path("on_disk_payload")),
            json(params.path("vectors")),
            json(params.path("sparse_vectors")),
            json(info.path("payload_schema")),
        };
    }

    private static Object[] snapshotRow(String collection, JsonNode snapshot) {
        return new Object[]{
            collection,
            text(snapshot.path("name")),
            text(snapshot.path("creation_time")),
            number(snapshot.path("size")),
            text(snapshot.path("checksum")),
        };
    }

    private static Object[] shardRow(String collection, JsonNode shard, String placement,
                                     Long peer) {
        return new Object[]{
            collection,
            number(shard.path("shard_id")),
            text(shard.path("shard_key")),
            placement,
            peer,
            text(shard.path("state")),
            number(shard.path("points_count")),
        };
    }

    /**
     * Every value under a node, as a row of {@link SystemTable#TELEMETRY}. An object or a list
     * with nothing in it is a row of its own, so a path Qdrant sent is never missing from the
     * table just because it holds nothing.
     */
    private static void flatten(JsonNode node, String path, List<Object[]> rows) {
        if (node.isObject() && !node.isEmpty()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                flatten(field.getValue(), path.isEmpty() ? field.getKey()
                    : path + "." + field.getKey(), rows);
            }

            return;
        }

        if (node.isArray() && !node.isEmpty()) {
            for (int i = 0; i < node.size(); i++) flatten(node.get(i), path + "[" + i + "]", rows);
            return;
        }

        rows.add(new Object[]{path, leaf(node), valueType(node)});
    }

    private static String leaf(JsonNode node) {
        if (node.isNull()) return null;

        return node.isContainerNode() ? node.toString() : node.asText();
    }

    private static String valueType(JsonNode node) {
        if (node.isNull()) return "null";
        if (node.isBoolean()) return "boolean";
        if (node.isNumber()) return "number";
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";

        return "string";
    }

    // Calls

    private List<String> collectionNames() throws SQLException {
        List<String> names = new ArrayList<>(gateway.listCollections());
        names.sort(null);

        return names;
    }

    /**
     * What one of a collection's endpoints answers.
     *
     * @param suffix what follows the collection in the path, such as {@code /snapshots}
     */
    private JsonNode perCollection(String collection, String suffix) throws SQLException {
        return skipMissing(() -> gateway.request(HttpMethod.GET,
            "/collections/" + segment(collection) + suffix, null));
    }

    /**
     * A call to Qdrant, for {@link #skipMissing}.
     */
    private interface Call {
        JsonNode get() throws SQLException;
    }

    /**
     * What a call answers, or null when it was about a collection that is gone since the
     * listing, so a collection deleted mid-statement is left out rather than failing it.
     */
    private static JsonNode skipMissing(Call call) throws SQLException {
        try {
            return call.get();
        } catch (SQLException e) {
            if (MISSING_COLLECTION.equals(e.getSQLState())) return null;

            throw e;
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // Values

    /**
     * A field as text. Qdrant answers some fields with either a string or an object, such as
     * {@code optimizer_status}, which is {@code "ok"} or an object holding the error, so an
     * object becomes its JSON. A shard key, which is a string or a whole number, becomes its
     * own text either way.
     */
    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;

        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static Long number(JsonNode node) {
        return node.isNumber() ? node.longValue() : null;
    }

    private static Boolean flag(JsonNode node) {
        return node.isBoolean() ? node.booleanValue() : null;
    }

    private static String json(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.toString();
    }

    /**
     * A peer id, which Qdrant writes as the key of its peer map, or null when it isn't a number
     * the driver can read.
     */
    private static Long id(String key) {
        try {
            return Long.valueOf(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Row order: nulls first, so a snapshot of the whole storage sorts before any collection's

    private static Comparator<Object[]> byText(int column) {
        return Comparator.comparing(row -> (String) row[column],
            Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    private static Comparator<Object[]> byNumber(int column) {
        return Comparator.comparing(row -> (Long) row[column],
            Comparator.nullsFirst(Comparator.naturalOrder()));
    }
}
