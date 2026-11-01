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
                "The collection the alias points at")));

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
