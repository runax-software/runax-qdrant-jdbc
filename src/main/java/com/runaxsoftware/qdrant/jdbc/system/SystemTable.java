package com.runaxsoftware.qdrant.jdbc.system;

import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The read-only tables that describe the server itself, in the {@code system} schema next to
 * the {@code collections} schema the collections sit in, so their names can't clash with a
 * collection's. Each is one Qdrant REST answer seen as rows: {@code SELECT * FROM
 * system.collections} reads Qdrant's collection API, not any points.
 *
 * <p>Their columns are fixed, so the driver plans a statement that reads one without calling
 * Qdrant, and {@code DatabaseMetaData} describes them without calling it either. Values that
 * Qdrant leaves out, such as the counts of a collection that isn't ready, are null.
 *
 * <p>Objects Qdrant answers with, such as a collection's vector parameters, are JSON columns
 * holding what Qdrant sent, rather than columns per field: their shape depends on the Qdrant
 * version and on how the collection was created.
 *
 * <p>Two of these aren't tabular at Qdrant's end and are projected rather than mapped:
 * {@link #TELEMETRY} is a tree, so each row is one leaf of it named by its path, and
 * {@link #METRICS} comes from Qdrant's Prometheus endpoint, so each row is one sample.
 * {@link #HEALTH} is three endpoints seen as one table, a row for each.
 */
public enum SystemTable {

    /**
     * Every collection, as Qdrant's collection API describes it, by name.
     */
    COLLECTIONS("collections", "Every collection on the server, with its state and parameters",
        List.of(
            SystemColumn.of("name", ColumnType.TEXT,
                "The collection name"),
            SystemColumn.of("status", ColumnType.TEXT,
                "green, yellow, grey, or red"),
            SystemColumn.of("optimizer_status", ColumnType.TEXT,
                "ok, or the error the optimizer reported"),
            SystemColumn.of("points_count", ColumnType.INTEGER,
                "Points, as Qdrant last counted them"),
            SystemColumn.of("indexed_vectors_count", ColumnType.INTEGER,
                "Vectors in the collection's vector index"),
            SystemColumn.of("segments_count", ColumnType.INTEGER,
                "Segments the points are stored in"),
            SystemColumn.of("shard_number", ColumnType.INTEGER,
                "Shards the collection is split into"),
            SystemColumn.of("replication_factor", ColumnType.INTEGER,
                "Copies kept of each shard"),
            SystemColumn.of("write_consistency_factor", ColumnType.INTEGER,
                "Shard copies a write must reach"),
            SystemColumn.of("on_disk_payload", ColumnType.BOOLEAN,
                "Whether payloads are kept on disk rather than in memory"),
            SystemColumn.of("vectors", ColumnType.JSON,
                "The dense vectors' parameters, as Qdrant sends them"),
            SystemColumn.of("sparse_vectors", ColumnType.JSON,
                "The sparse vectors' parameters, as Qdrant sends them"),
            SystemColumn.of("payload_indexes", ColumnType.JSON,
                "Payload field path to the index on it, as Qdrant sends them"))),

    /**
     * Every alias, with the collection it points at, by alias name. The same aliases that
     * {@code DatabaseMetaData} lists as views.
     */
    ALIASES("aliases", "Every alias, with the collection it points at",
        List.of(
            SystemColumn.of("alias_name", ColumnType.TEXT,
                "The alias name"),
            SystemColumn.of("collection_name", ColumnType.TEXT,
                "The collection the alias points at"))),

    /**
     * Every snapshot, a collection's and the whole storage's together: Qdrant describes both
     * the same way, so one table holds them, and {@code collection_name} says which is which.
     */
    SNAPSHOTS("snapshots", "Every snapshot, of a collection or of the whole storage",
        List.of(
            SystemColumn.of("collection_name", ColumnType.TEXT,
                "The collection the snapshot is of, or null for a whole-storage snapshot"),
            SystemColumn.of("name", ColumnType.TEXT,
                "The snapshot file name, which names it in the snapshot API"),
            SystemColumn.of("creation_time", ColumnType.DATETIME,
                "When Qdrant took the snapshot, in UTC"),
            SystemColumn.of("size", ColumnType.INTEGER,
                "The snapshot file's size in bytes"),
            SystemColumn.of("checksum", ColumnType.TEXT,
                "The snapshot file's SHA-256 checksum"))),

    /**
     * Every shard key of every collection. Only a collection created with custom sharding has
     * any, and Qdrant only lets one be created when it runs as a cluster, so this is empty on
     * a standalone server.
     */
    SHARD_KEYS("shard_keys", "Every collection's shard keys, for custom sharding",
        List.of(
            SystemColumn.of("collection_name", ColumnType.TEXT,
                "The collection the shard key belongs to"),
            SystemColumn.of("shard_key", ColumnType.TEXT,
                "The key, as text; Qdrant takes a string or a whole number"))),

    /**
     * Every shard of every collection, each copy of it its own row: where it is, which peer
     * holds it, and what state that copy is in.
     */
    SHARDS("shards", "Every shard of every collection, and the peer holding each copy",
        List.of(
            SystemColumn.of("collection_name", ColumnType.TEXT,
                "The collection the shard belongs to"),
            SystemColumn.of("shard_id", ColumnType.INTEGER,
                "The shard's id within the collection"),
            SystemColumn.of("shard_key", ColumnType.TEXT,
                "The shard key the shard holds, or null unless the collection is custom-sharded"),
            SystemColumn.of("placement", ColumnType.TEXT,
                "local for a copy on the peer the driver is connected to, remote for one elsewhere"),
            SystemColumn.of("peer_id", ColumnType.INTEGER,
                "The peer holding this copy"),
            SystemColumn.of("state", ColumnType.TEXT,
                "The replica state Qdrant reports, such as Active, Dead, or Partial"),
            SystemColumn.of("points_count", ColumnType.INTEGER,
                "Points in the shard; Qdrant only counts them for a local copy"))),

    /**
     * The cluster as the peer the driver is connected to sees it, always one row: a standalone
     * server answers with a status of {@code disabled} and nothing else, so the rest is null.
     */
    CLUSTER("cluster", "The cluster as the connected peer sees it, in one row",
        List.of(
            SystemColumn.of("status", ColumnType.TEXT,
                "enabled when the server runs as a cluster, disabled when standalone"),
            SystemColumn.of("peer_id", ColumnType.INTEGER,
                "The id of the peer the driver is connected to"),
            SystemColumn.of("peer_count", ColumnType.INTEGER,
                "Peers in the cluster, as rows in system.peers"),
            SystemColumn.of("raft_term", ColumnType.INTEGER,
                "The current Raft term"),
            SystemColumn.of("raft_commit", ColumnType.INTEGER,
                "The last committed Raft operation this peer knows of"),
            SystemColumn.of("raft_pending_operations", ColumnType.INTEGER,
                "Consensus operations still to be applied on this peer"),
            SystemColumn.of("raft_leader", ColumnType.INTEGER,
                "The peer leading the current term"),
            SystemColumn.of("raft_role", ColumnType.TEXT,
                "This peer's role in the current term, such as Leader or Follower"),
            SystemColumn.of("raft_is_voter", ColumnType.BOOLEAN,
                "Whether this peer votes in elections rather than only learning"),
            SystemColumn.of("consensus_thread_status", ColumnType.TEXT,
                "working, stopped, or stopped_with_err"),
            SystemColumn.of("consensus_thread_updated", ColumnType.DATETIME,
                "When the consensus thread last made progress"),
            SystemColumn.of("consensus_thread_error", ColumnType.TEXT,
                "Why the consensus thread stopped, when it stopped with an error"),
            SystemColumn.of("message_send_failures", ColumnType.JSON,
                "Consecutive consensus message failures by peer address, as Qdrant sends them"))),

    /**
     * Every peer of the cluster, by id. Empty on a standalone server, which has no peers at
     * all rather than one of itself.
     */
    PEERS("peers", "Every peer of the cluster, with its address",
        List.of(
            SystemColumn.of("peer_id", ColumnType.INTEGER,
                "The peer's id"),
            SystemColumn.of("uri", ColumnType.TEXT,
                "The peer's internal address, which the peers talk to each other on"),
            SystemColumn.of("is_this_peer", ColumnType.BOOLEAN,
                "Whether this is the peer the driver is connected to"),
            SystemColumn.of("is_leader", ColumnType.BOOLEAN,
                "Whether this peer leads the current Raft term"))),

    /**
     * Qdrant's telemetry, one row per value in it: it is a tree whose shape depends on the
     * Qdrant version, so the rows are leaves named by their path rather than fixed columns.
     */
    TELEMETRY("telemetry", "Qdrant's telemetry, as one row per value in it",
        List.of(
            SystemColumn.of("path", ColumnType.TEXT,
                "Where the value sits in the telemetry, as dotted keys, such as app.version"),
            SystemColumn.of("value", ColumnType.TEXT,
                "The value as text, whatever JSON type it has"),
            SystemColumn.of("value_type", ColumnType.TEXT,
                "string, number, boolean, or null: how to read the value"))),

    /**
     * The telemetry of the whole cluster, as the connected peer gathers it from the others, one
     * row per value like {@link #TELEMETRY}. A server that isn't a cluster has none.
     */
    CLUSTER_TELEMETRY("cluster_telemetry",
        "The cluster's telemetry, gathered from every peer, as one row per value in it",
        List.of(
            SystemColumn.of("path", ColumnType.TEXT,
                "Where the value sits in the telemetry, as dotted keys, such as "
                    + "cluster.number_of_peers"),
            SystemColumn.of("value", ColumnType.TEXT,
                "The value as text, whatever JSON type it has"),
            SystemColumn.of("value_type", ColumnType.TEXT,
                "string, number, boolean, or null: how to read the value"))),

    /**
     * Qdrant's Prometheus metrics, one row per sample. The only table read from a text answer
     * rather than JSON.
     */
    METRICS("metrics", "Qdrant's Prometheus metrics, as one row per sample",
        List.of(
            SystemColumn.of("name", ColumnType.TEXT,
                "The metric name, as Prometheus names it"),
            SystemColumn.of("labels", ColumnType.JSON,
                "The sample's labels as a JSON object, or null when it has none"),
            SystemColumn.of("value", ColumnType.FLOAT,
                "The sample's value"),
            SystemColumn.of("type", ColumnType.TEXT,
                "The metric's declared type: counter, gauge, histogram, or summary"),
            SystemColumn.of("help", ColumnType.TEXT,
                "The metric's declared description"))),

    /**
     * The performance problems and configuration suggestions Qdrant has collected, by id.
     */
    ISSUES("issues", "The problems and suggestions Qdrant reports about itself",
        List.of(
            SystemColumn.of("id", ColumnType.TEXT,
                "Qdrant's id for the issue, which starts with its code, such as UNINDEXED_FIELD"),
            SystemColumn.of("description", ColumnType.TEXT,
                "What Qdrant found"),
            SystemColumn.of("related_collection", ColumnType.TEXT,
                "The collection the issue is about, when it is about one"),
            SystemColumn.of("timestamp", ColumnType.DATETIME,
                "When Qdrant raised the issue"),
            SystemColumn.of("solution", ColumnType.JSON,
                "What Qdrant suggests doing about it, as Qdrant sends it"))),

    /**
     * Qdrant's three health probes, one row each. A probe that fails is a row saying so, not an
     * error: the table is for asking whether the server is up and ready.
     */
    HEALTH("health", "Qdrant's health probes, as one row per probe",
        List.of(
            SystemColumn.of("probe", ColumnType.TEXT,
                "The probe: healthz, livez, or readyz"),
            SystemColumn.of("ok", ColumnType.BOOLEAN,
                "Whether the check passed"),
            SystemColumn.of("status_code", ColumnType.INTEGER,
                "The HTTP status Qdrant answered the probe with, 200 when it passed"),
            SystemColumn.of("message", ColumnType.TEXT,
                "What Qdrant said, such as 'all shards are ready'"))),

    /**
     * Every collection's optimizations: the ones running, the ones Qdrant expects to run next,
     * and the latest it finished. A collection with nothing to optimize has no rows.
     */
    OPTIMIZATIONS("optimizations",
        "Every collection's running, queued, and latest completed optimizations",
        List.of(
            SystemColumn.of("collection_name", ColumnType.TEXT,
                "The collection being optimized"),
            SystemColumn.of("state", ColumnType.TEXT,
                "running, queued, or completed: which of Qdrant's lists the optimization is in"),
            SystemColumn.of("uuid", ColumnType.TEXT,
                "The optimization's id, which the segment it builds takes; null while queued"),
            SystemColumn.of("optimizer", ColumnType.TEXT,
                "The optimizer doing the work, such as indexing, merge, or vacuum"),
            SystemColumn.of("status", ColumnType.TEXT,
                "optimizing, done, cancelled, or error; null while queued"),
            SystemColumn.of("status_detail", ColumnType.TEXT,
                "Why an optimization was cancelled, or the error it failed with"),
            SystemColumn.of("segments_count", ColumnType.INTEGER,
                "How many segments the optimization takes"),
            SystemColumn.of("points_count", ColumnType.INTEGER,
                "The points in those segments"),
            SystemColumn.of("started_at", ColumnType.DATETIME,
                "When the optimization started"),
            SystemColumn.of("finished_at", ColumnType.DATETIME,
                "When it finished, or null while it runs"),
            SystemColumn.of("duration_sec", ColumnType.FLOAT,
                "How long a finished optimization took, in seconds"),
            SystemColumn.of("segments", ColumnType.JSON,
                "The segments, each with its id and point count, as Qdrant sends them"),
            SystemColumn.of("progress", ColumnType.JSON,
                "The optimization's steps as a tree, each with its times and units done"))),

    /**
     * The server's quotas in one row: the limits past which Qdrant refuses writes, and how much
     * of each resource is used now. ALTER SYSTEM SET changes the limits.
     */
    QUOTAS("quotas", "The server's limits on memory and disk, and how much is used, in one row",
        List.of(
            SystemColumn.of("enabled", ColumnType.BOOLEAN,
                "Whether the limits are enforced"),
            SystemColumn.of("max_resident_memory_percent", ColumnType.INTEGER,
                "The share of memory past which Qdrant refuses writes, or null for no limit"),
            SystemColumn.of("max_disk_usage_percent", ColumnType.INTEGER,
                "The share of the storage disk past which Qdrant refuses writes, or null"),
            SystemColumn.of("release_margin_percent", ColumnType.INTEGER,
                "How far under a limit usage falls before writes are taken again, or null for "
                    + "Qdrant's default"),
            SystemColumn.of("resident_memory_percent", ColumnType.INTEGER,
                "The share of memory the server uses now"),
            SystemColumn.of("disk_usage_percent", ColumnType.INTEGER,
                "The share of the storage disk used now")));

    /**
     * The schema every system table is in.
     */
    public static final String SCHEMA = "system";

    private final String tableName;
    private final String remarks;
    private final List<SystemColumn> columns;

    SystemTable(String tableName, String remarks, List<SystemColumn> columns) {
        this.tableName = tableName;
        this.remarks = remarks;
        this.columns = columns;
    }

    /**
     * The table name, as it appears in SQL and in {@code DatabaseMetaData}.
     */
    public String tableName() {
        return tableName;
    }

    /**
     * A description shown as the table comment.
     */
    public String remarks() {
        return remarks;
    }

    /**
     * The columns, in order. A row has one value per column, in the same order.
     */
    public List<SystemColumn> columns() {
        return columns;
    }

    /**
     * Finds a column by name, ignoring case, as the driver names these columns itself.
     */
    public Optional<SystemColumn> column(String name) {
        return columns.stream().filter(column -> column.name().equalsIgnoreCase(name)).findFirst();
    }

    /**
     * Where a column's values sit in a row the reader built, found by the column's
     * {@link SystemColumn#source()}, so an aliased column still reads its own values.
     *
     * @throws IllegalArgumentException if the column isn't one of this table's
     */
    public int indexOf(SystemColumn column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).source().equals(column.source())) return i;
        }

        throw new IllegalArgumentException(
            "Column '" + column.source() + "' isn't one of " + SCHEMA + "." + tableName + "'s");
    }

    /**
     * The system table with this name, ignoring case, or empty when there's none.
     */
    public static Optional<SystemTable> named(String name) {
        if (name == null) return Optional.empty();
        String lower = name.toLowerCase(Locale.ROOT);

        return Arrays.stream(values()).filter(table -> table.tableName.equals(lower)).findFirst();
    }

    /**
     * Whether the name is the system schema, ignoring case: the driver names it itself, so it
     * matches however a tool spells it, unlike a collection name, which Qdrant matches exactly.
     */
    public static boolean isSystemSchema(String schema) {
        return schema != null && schema.equalsIgnoreCase(SCHEMA);
    }
}
