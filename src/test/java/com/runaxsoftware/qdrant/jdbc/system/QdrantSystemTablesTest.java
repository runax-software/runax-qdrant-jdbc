package com.runaxsoftware.qdrant.jdbc.system;

import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.collectionInfo;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.dense;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.localShard;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.remoteShard;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.snapshotDescription;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.unnamedVector;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.Endpoint;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class QdrantSystemTablesTest {

    private FakeQdrantServer server;
    private QdrantGateway gateway;
    private QdrantSystemTables tables;

    @BeforeEach
    void start() throws Exception {
        server = FakeQdrantServer.start();
        gateway = QdrantGateway.open("localhost", server.port(), false, null,
            Duration.ofSeconds(5), "test-agent/1.0");
        tables = new QdrantSystemTables(gateway);
    }

    @AfterEach
    void stop() {
        gateway.close();
        server.close();
    }

    // Collections

    @Test
    void readsEveryCollectionSortedByName() throws SQLException {
        server.collection("products", unnamedVector(4, "Cosine", Map.of()), List.of())
            .collection("Mixed-Case", unnamedVector(2, "Dot", Map.of()), List.of())
            .collection("articles", unnamedVector(3, "Euclid", Map.of()), List.of());

        assertEquals(List.of("Mixed-Case", "articles", "products"), names(SystemTable.COLLECTIONS));
    }

    @Test
    void readsACollectionsStateAndParameters() throws SQLException {
        server.collection("docs", collectionInfo(Json.object("dense", dense(3, "Euclid")),
            Json.object("keywords", Json.object()), Map.of("lang", "keyword")), List.of());

        assertArrayEquals(new Object[]{
            "docs",
            "green",
            "ok",
            42L,
            null,                                               // Qdrant sends no indexed count
            1L,
            1L,
            1L,
            null,                                               // nor a write consistency factor
            true,
            "{\"dense\":{\"size\":3,\"distance\":\"Euclid\"}}",
            "{\"keywords\":{}}",
            "{\"lang\":{\"data_type\":\"keyword\",\"points\":42}}",
        }, row(SystemTable.COLLECTIONS, "docs"));
    }

    @Test
    void readsAnObjectStatusAsItsJson() throws SQLException {
        server.collection("docs", collectionInfo(dense(2, "Cosine"), null, Map.of())
            .set("optimizer_status", Json.object("error", "No space left")), List.of());

        assertEquals("{\"error\":\"No space left\"}",
            value(SystemTable.COLLECTIONS, "docs", "optimizer_status"));
    }

    @Test
    void readsMissingFieldsAsNull() throws SQLException {
        server.collection("bare", Json.object(), List.of());

        Object[] row = row(SystemTable.COLLECTIONS, "bare");

        assertEquals("bare", row[0]);
        for (int i = 1; i < row.length; i++) {
            assertEquals(null, row[i], SystemTable.COLLECTIONS.columns().get(i).name());
        }
    }

    @Test
    void leavesOutACollectionDeletedSinceTheListing() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .listedButMissing("ghost");

        assertEquals(List.of("docs"), names(SystemTable.COLLECTIONS));
    }

    @Test
    void readsOneCollectionAtATime() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .collection("notes", unnamedVector(2, "Cosine", Map.of()), List.of());

        tables.read(SystemTable.COLLECTIONS);

        assertEquals(1, server.calls(Endpoint.COLLECTIONS));
        assertEquals(2, server.calls(Endpoint.COLLECTION));
    }

    @Test
    void readsNoRowsFromAServerWithoutCollections() throws SQLException {
        assertEquals(List.of(), tables.read(SystemTable.COLLECTIONS));
        assertEquals(List.of(), tables.read(SystemTable.ALIASES));
        assertEquals(List.of(), tables.read(SystemTable.SNAPSHOTS));
        assertEquals(List.of(), tables.read(SystemTable.SHARD_KEYS));
        assertEquals(List.of(), tables.read(SystemTable.SHARDS));
        assertEquals(List.of(), tables.read(SystemTable.PEERS));
        assertEquals(List.of(), tables.read(SystemTable.ISSUES));
    }

    @Test
    void reportsWhatQdrantReportsAboutTheListing() {
        server.fail(Endpoint.COLLECTIONS, 500, "Service internal error");

        SQLException error = assertThrows(SQLException.class,
            () -> tables.read(SystemTable.COLLECTIONS));

        assertEquals("HY000", error.getSQLState());
        assertTrue(error.getMessage().contains("Service internal error"), error.getMessage());
    }

    @Test
    void reportsWhatQdrantReportsAboutACollection() {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .fail(Endpoint.COLLECTION, 500, "Service internal error");

        SQLException error = assertThrows(SQLException.class,
            () -> tables.read(SystemTable.COLLECTIONS));

        assertEquals("HY000", error.getSQLState());
    }

    // Aliases

    @Test
    void readsEveryAliasSortedByName() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .collection("notes", unnamedVector(2, "Cosine", Map.of()), List.of())
            .alias("current", "docs")
            .alias("Best", "notes");

        List<Object[]> rows = tables.read(SystemTable.ALIASES);

        assertEquals(2, rows.size());
        assertArrayEquals(new Object[]{"Best", "notes"}, rows.get(0));
        assertArrayEquals(new Object[]{"current", "docs"}, rows.get(1));
    }

    // Snapshots

    @Test
    void readsTheWholeStoragesSnapshotsBeforeEachCollectionsOwn() throws SQLException {
        server.collection("products", unnamedVector(2, "Cosine", Map.of()), List.of())
            .collection("articles", unnamedVector(2, "Cosine", Map.of()), List.of())
            .snapshot(null, snapshotDescription("full-snapshot-2026-11-01-17-05-29-4.snapshot",
                "2026-11-01T17:05:29", 1074176, "1393beda"))
            .snapshot("products", snapshotDescription("products-2.snapshot",
                "2026-11-01T17:06:00", 2048, "b2"))
            .snapshot("products", snapshotDescription("products-1.snapshot",
                "2026-11-01T17:05:00", 1024, "a1"))
            .snapshot("articles", snapshotDescription("articles-1.snapshot",
                "2026-11-01T17:04:00", 512, null));

        List<Object[]> rows = tables.read(SystemTable.SNAPSHOTS);

        assertEquals(List.of("null/full-snapshot-2026-11-01-17-05-29-4.snapshot",
            "articles/articles-1.snapshot", "products/products-1.snapshot",
            "products/products-2.snapshot"), labelled(rows));
        assertArrayEquals(new Object[]{null,
            "full-snapshot-2026-11-01-17-05-29-4.snapshot", "2026-11-01T17:05:29", 1074176L,
            "1393beda"}, rows.get(0));
        assertArrayEquals(new Object[]{"articles", "articles-1.snapshot", "2026-11-01T17:04:00",
            512L, null}, rows.get(1));
    }

    @Test
    void leavesOutTheSnapshotsOfACollectionDeletedSinceTheListing() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .snapshot("docs", snapshotDescription("docs-1.snapshot", "2026-11-01T17:05:00", 8, "a"))
            .listedButMissing("ghost");

        assertEquals(List.of("docs"), names(SystemTable.SNAPSHOTS));
    }

    @Test
    void readsTheSnapshotsOfOneCollectionAtATime() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .collection("notes", unnamedVector(2, "Cosine", Map.of()), List.of());

        tables.read(SystemTable.SNAPSHOTS);

        assertEquals(1, server.calls(Endpoint.SNAPSHOTS));
        assertEquals(2, server.calls(Endpoint.COLLECTION_SNAPSHOTS));
    }

    // Shard keys

    @Test
    void readsNoShardKeysFromCollectionsThatArentCustomSharded() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of());

        assertEquals(List.of(), tables.read(SystemTable.SHARD_KEYS));
    }

    @Test
    void readsEveryShardKeyByCollectionAndKey() throws SQLException {
        server.collection("notes", unnamedVector(2, "Cosine", Map.of()), List.of())
            .collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .shardKeys("docs", "eu", 7)
            .shardKeys("notes", "us");

        List<Object[]> rows = tables.read(SystemTable.SHARD_KEYS);

        assertEquals(3, rows.size());
        assertArrayEquals(new Object[]{"docs", "7"}, rows.get(0));
        assertArrayEquals(new Object[]{"docs", "eu"}, rows.get(1));
        assertArrayEquals(new Object[]{"notes", "us"}, rows.get(2));
    }

    // Shards

    @Test
    void readsALocalShardAsHeldByThePeerThatAnswered() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of());

        assertArrayEquals(new Object[]{"docs", 0L, null, "local", FakeQdrantServer.PEER_ID,
            "Active", 42L}, tables.read(SystemTable.SHARDS).get(0));
    }

    @Test
    void readsEveryCopyOfAShardWithItsKeyAndPeer() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .collectionCluster("docs", FakeQdrantServer.collectionCluster(
                List.of(localShard(2, 7, 0, "Active"), localShard(1, "eu", 3, "Active")),
                List.of(remoteShard(1, "eu", 3354747850129501L, "Partial"))));

        List<Object[]> rows = tables.read(SystemTable.SHARDS);

        assertEquals(3, rows.size());
        assertArrayEquals(new Object[]{"docs", 1L, "eu", "local", FakeQdrantServer.PEER_ID,
            "Active", 3L}, rows.get(0));
        assertArrayEquals(new Object[]{"docs", 1L, "eu", "remote", 3354747850129501L,
            "Partial", null}, rows.get(1), "Qdrant counts no points for a remote copy");
        assertArrayEquals(new Object[]{"docs", 2L, "7", "local", FakeQdrantServer.PEER_ID,
            "Active", 0L}, rows.get(2));
    }

    // Cluster and peers

    @Test
    void readsOneRowFromAStandaloneServerWithNothingButItsStatus() throws SQLException {
        List<Object[]> rows = tables.read(SystemTable.CLUSTER);

        assertEquals(1, rows.size());
        assertEquals("disabled", rows.get(0)[0]);
        for (int i = 1; i < rows.get(0).length; i++) {
            assertEquals(null, rows.get(0)[i], SystemTable.CLUSTER.columns().get(i).name());
        }

        assertEquals(List.of(), tables.read(SystemTable.PEERS));
    }

    @Test
    void readsTheRaftStateAndPeerCountOfACluster() throws SQLException {
        server.cluster(FakeQdrantServer.enabledCluster(peers()));

        assertArrayEquals(new Object[]{"enabled", FakeQdrantServer.PEER_ID, 2L, 1L, 6L, 0L,
            FakeQdrantServer.PEER_ID, "Leader", true, "working",
            "2026-11-01T17:06:39.195643502Z", null, "{}"}, tables.read(SystemTable.CLUSTER).get(0));
    }

    @Test
    void readsEveryPeerByIdAndSaysWhichAnsweredAndWhichLeads() throws SQLException {
        server.cluster(FakeQdrantServer.enabledCluster(peers()));

        List<Object[]> rows = tables.read(SystemTable.PEERS);

        assertEquals(2, rows.size());
        assertArrayEquals(new Object[]{1321113523610302L, "http://qp1:6335/", false, false},
            rows.get(0));
        assertArrayEquals(new Object[]{FakeQdrantServer.PEER_ID, "http://qp2:6335/", true, true},
            rows.get(1));
    }

    // Telemetry

    @Test
    void readsOneRowPerValueOfTheTelemetryByPath() throws SQLException {
        server.telemetry(Json.object(
            "id", "fbada747", "app", Json.object("version", "1.20.0", "startup", null),
            "cluster", Json.object("enabled", false),
            "collections", Json.object("number_of_collections", 3),
            "requests", Json.object("rest", Json.object(
                "GET /collections", Json.object("avg_duration_micros", 24.5)))));

        List<Object[]> rows = tables.read(SystemTable.TELEMETRY);

        assertEquals(List.of(
            "app.startup=null null",
            "app.version=1.20.0 string",
            "cluster.enabled=false boolean",
            "collections.number_of_collections=3 number",
            "id=fbada747 string",
            "requests.rest.GET /collections.avg_duration_micros=24.5 number"), flattened(rows));
    }

    @Test
    void readsAnEmptyObjectOrListInTheTelemetryAsItsOwnRow() throws SQLException {
        server.telemetry(Json.object("message_send_failures", Json.object(),
            "shard_transfers", Json.array(),
            "peers", Json.array(Json.object("uri", "http://qp1:6335/"))));

        assertEquals(List.of(
            "message_send_failures={} object",
            "peers[0].uri=http://qp1:6335/ string",
            "shard_transfers=[] array"), flattened(tables.read(SystemTable.TELEMETRY)));
    }

    // Metrics

    @Test
    void readsEverySampleOfTheMetricsText() throws SQLException {
        List<Object[]> rows = tables.read(SystemTable.METRICS);

        assertArrayEquals(new Object[]{"app_info", "{\"name\":\"qdrant\",\"version\":\"1.20.0\"}",
            1.0, "gauge", "information about qdrant server"}, rows.get(0));
        assertArrayEquals(new Object[]{"collection_points", "{\"id\":\"articles\"}", 7.0, "gauge",
            "approximate amount of points per collection"}, rows.get(1));
        assertEquals(List.of("app_info", "collection_points", "collection_points",
            "rest_responses_duration_seconds_bucket", "rest_responses_duration_seconds_bucket",
            "rest_responses_duration_seconds_count", "rest_responses_duration_seconds_sum",
            "rest_responses_total"), names(SystemTable.METRICS));
    }

    /**
     * A histogram's samples are named with a suffix, and Qdrant declares the type and the help
     * once, for the name without it.
     */
    @Test
    void readsAHistogramsSamplesUnderItsFamilysTypeAndHelp() throws SQLException {
        Object[] bucket = row(SystemTable.METRICS, "rest_responses_duration_seconds_bucket");

        assertEquals("histogram", value(bucket, SystemTable.METRICS, "type"));
        assertEquals("duration of responses through the rest API",
            value(bucket, SystemTable.METRICS, "help"));
    }

    @Test
    void readsTheValuesPrometheusSpellsItsOwnWay() throws SQLException {
        server.metrics("""
            # TYPE odd gauge
            odd{at="infinity"} +Inf
            odd{at="minus infinity"} -Inf
            odd{at="nowhere"} NaN
            odd{at="exponent"} 1.5e-8
            """);

        // By labels, so "exponent" sorts before "infinity", "minus infinity", and "nowhere"
        assertEquals(List.of(1.5e-8, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.NaN), values(tables.read(SystemTable.METRICS), 2));
    }

    @Test
    void readsASampleWithoutLabelsAndOneWithoutATypeOfItsOwn() throws SQLException {
        server.metrics("""
            # HELP app_status_recovery_mode features enabled in qdrant server
            # TYPE app_status_recovery_mode gauge
            app_status_recovery_mode 0
            undeclared{a="b\\"c\\\\d"} 2
            """);

        List<Object[]> rows = tables.read(SystemTable.METRICS);

        assertArrayEquals(new Object[]{"app_status_recovery_mode", null, 0.0, "gauge",
            "features enabled in qdrant server"}, rows.get(0));
        assertArrayEquals(new Object[]{"undeclared", "{\"a\":\"b\\\"c\\\\d\"}", 2.0, null, null},
            rows.get(1));
    }

    /**
     * The metrics format is Qdrant's to change, so a line the driver can't read is left out
     * rather than failing the statement and hiding the metrics it can read.
     */
    @Test
    void leavesOutAMetricsLineItCantRead() throws SQLException {
        server.metrics("""
            # TYPE fine gauge
            #
            # HELP only two words
            unterminated{a="b 1
            no_value
            fine 3

            """);

        assertArrayEquals(new Object[]{"fine", null, 3.0, "gauge", null},
            tables.read(SystemTable.METRICS).get(0));
        assertEquals(1, tables.read(SystemTable.METRICS).size());
    }

    // Issues

    @Test
    void readsEveryIssueByIdWithItsSolutionAsJson() throws SQLException {
        server.issue(FakeQdrantServer.issue("UNINDEXED_FIELD/docs/lang",
                "Unindexed field 'lang' might be slowing queries down", "docs"))
            .issue(FakeQdrantServer.issue("UNINDEXED_FIELD/docs/city",
                "Unindexed field 'city' might be slowing queries down", null));

        List<Object[]> rows = tables.read(SystemTable.ISSUES);

        assertEquals(List.of("UNINDEXED_FIELD/docs/city", "UNINDEXED_FIELD/docs/lang"),
            names(SystemTable.ISSUES));
        assertEquals(null, rows.get(0)[2]);
        assertEquals("docs", rows.get(1)[2]);
        assertEquals("2026-11-01T17:10:01.997331971Z", rows.get(1)[3]);
        assertTrue(((String) rows.get(1)[4]).startsWith("{\"immediate\""), rows.get(1)[4] + "");
    }

    // Optimizations

    @Test
    void readsEveryOptimizationByCollectionInQdrantsOrder() throws SQLException {
        server.collection("notes", unnamedVector(2, "Cosine", Map.of()), List.of())
            .collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .optimizations("notes", Json.object("completed", Json.array(Json.object(
                "uuid", "94b34892-0160-4ec3-97ee-4c0380571208", "optimizer", "indexing",
                "status", "done",
                "segments", Json.array(Json.object("uuid", "9080adf9", "points_count", 2000)),
                "progress", Json.object("name", "Segment Optimizing",
                    "started_at", "2026-11-14T17:16:44.504784136Z",
                    "finished_at", "2026-11-14T17:16:44.605268803Z",
                    "duration_sec", 0.100484458)))))
            .optimizations("docs", Json.object(
                "running", Json.array(Json.object(
                    "uuid", "3a51151e-76ec-4c46-a0a5-4631f0c8ace7", "optimizer", "indexing",
                    "status", "optimizing",
                    "segments", Json.array(Json.object("uuid", "ac25d4c2", "points_count", 2000),
                        Json.object("uuid", "94b34892", "points_count", 500)),
                    "progress", Json.object("name", "Segment Optimizing",
                        "started_at", "2026-11-14T17:16:44.621179345Z"))),
                "queued", Json.array(Json.object("optimizer", "merge",
                    "segments", Json.array(Json.object("uuid", "07f4905f", "points_count", 7)))),
                "completed", Json.array(Json.object(
                    "uuid", "7d91a1bb-f905-42f5-a940-f54e9438c684", "optimizer", "vacuum",
                    "status", Json.object("cancelled", "the collection was deleted"),
                    "segments", Json.array(),
                    "progress", Json.object("name", "Segment Optimizing")))));

        List<Object[]> rows = tables.read(SystemTable.OPTIMIZATIONS);

        assertEquals(4, rows.size());
        assertArrayEquals(new Object[]{"docs", "running", "3a51151e-76ec-4c46-a0a5-4631f0c8ace7",
            "indexing", "optimizing", null, 2L, 2500L, "2026-11-14T17:16:44.621179345Z", null,
            null}, Arrays.copyOf(rows.get(0), 11));
        assertTrue(((String) rows.get(0)[11]).startsWith("[{\"uuid\":\"ac25d4c2\""));
        assertTrue(((String) rows.get(0)[12]).startsWith("{\"name\":\"Segment Optimizing\""));

        assertArrayEquals(new Object[]{"docs", "queued", null, "merge", null, null, 1L, 7L, null,
            null, null}, Arrays.copyOf(rows.get(1), 11));

        assertArrayEquals(new Object[]{"docs", "completed",
            "7d91a1bb-f905-42f5-a940-f54e9438c684", "vacuum", "cancelled",
            "the collection was deleted", 0L, 0L, null, null, null},
            Arrays.copyOf(rows.get(2), 11));

        assertArrayEquals(new Object[]{"notes", "completed",
            "94b34892-0160-4ec3-97ee-4c0380571208", "indexing", "done", null, 1L, 2000L,
            "2026-11-14T17:16:44.504784136Z", "2026-11-14T17:16:44.605268803Z", 0.100484458},
            Arrays.copyOf(rows.get(3), 11));
    }

    @Test
    void asksForTheQueuedAndCompletedOptimizationsToo() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of());

        assertEquals(List.of(), tables.read(SystemTable.OPTIMIZATIONS));

        var request = server.requests().get(server.requests().size() - 1);
        assertEquals("/collections/docs/optimizations", request.path());
        assertEquals("with=queued,completed", request.query());
    }

    // Quotas

    @Test
    void readsTheQuotasAsOneRowOfLimitsAndUsage() throws SQLException {
        assertArrayEquals(new Object[]{false, null, null, null, 3L, 17L},
            tables.read(SystemTable.QUOTAS).get(0));

        server.quotas(Json.object("enabled", true, "max_resident_memory_percent", 80,
            "max_disk_usage_percent", 90, "release_margin_percent", 5));

        List<Object[]> rows = tables.read(SystemTable.QUOTAS);
        assertEquals(1, rows.size());
        assertArrayEquals(new Object[]{true, 80L, 90L, 5L, 3L, 17L}, rows.get(0));
    }

    // Health

    @Test
    void readsOneRowPerHealthProbe() throws SQLException {
        List<Object[]> rows = tables.read(SystemTable.HEALTH);

        assertEquals(3, rows.size());
        assertArrayEquals(new Object[]{"healthz", true, 200L, "healthz check passed"}, rows.get(0));
        assertArrayEquals(new Object[]{"livez", true, 200L, "healthz check passed"}, rows.get(1));
        assertArrayEquals(new Object[]{"readyz", true, 200L, "all shards are ready"}, rows.get(2));
        assertEquals(1, server.calls(Endpoint.HEALTHZ));
        assertEquals(1, server.calls(Endpoint.LIVEZ));
        assertEquals(1, server.calls(Endpoint.READYZ));
    }

    @Test
    void reportsAServerThatIsntReadyAsARowNotAnError() throws SQLException {
        server.failWithBody(Endpoint.READYZ, 503, "text/plain", "some shards are not ready");

        List<Object[]> rows = tables.read(SystemTable.HEALTH);

        assertArrayEquals(new Object[]{"healthz", true, 200L, "healthz check passed"}, rows.get(0));
        assertArrayEquals(new Object[]{"readyz", false, 503L, "some shards are not ready"},
            rows.get(2));
    }

    // Helpers

    private static Map<Long, String> peers() {
        Map<Long, String> peers = new LinkedHashMap<>();
        peers.put(FakeQdrantServer.PEER_ID, "http://qp2:6335/");
        peers.put(1321113523610302L, "http://qp1:6335/");

        return peers;
    }

    // "collection/snapshot", so a null collection is visible in the order
    private static List<String> labelled(List<Object[]> rows) {
        return rows.stream().map(row -> row[0] + "/" + row[1]).toList();
    }

    private static List<String> flattened(List<Object[]> rows) {
        return rows.stream().map(row -> row[0] + "=" + row[1] + " " + row[2]).toList();
    }

    private static List<Object> values(List<Object[]> rows, int column) {
        return rows.stream().map(row -> row[column]).toList();
    }

    private List<String> names(SystemTable table) throws SQLException {
        List<String> names = new ArrayList<>();
        for (Object[] row : tables.read(table)) names.add((String) row[0]);

        return names;
    }

    private Object[] row(SystemTable table, String name) throws SQLException {
        return tables.read(table).stream()
            .filter(row -> name.equals(row[0]))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No row for " + name));
    }

    private Object value(SystemTable table, String name, String column) throws SQLException {
        return value(row(table, name), table, column);
    }

    private static Object value(Object[] row, SystemTable table, String column) {
        return row[table.indexOf(table.column(column).orElseThrow())];
    }
}
