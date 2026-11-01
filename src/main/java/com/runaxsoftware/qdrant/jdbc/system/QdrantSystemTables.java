package com.runaxsoftware.qdrant.jdbc.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads the system tables from Qdrant's REST API, always afresh: they describe state that
 * changes without the driver, so nothing here is kept between statements.
 *
 * <p>{@link SystemTable#COLLECTIONS} lists the collections and then reads each one, because
 * Qdrant describes a collection only one at a time. That is one call per collection, so the
 * table costs more than the others; a collection deleted between the listing and its read is
 * left out rather than failing the statement.
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
        };
    }

    // One row per collection, by name, so a statement's rows don't depend on Qdrant's order
    private List<Object[]> collections() throws SQLException {
        List<String> names = new ArrayList<>(gateway.listCollections());
        names.sort(null);

        List<Object[]> rows = new ArrayList<>(names.size());
        for (String name : names) {
            JsonNode info = info(name);
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

    // The collection as Qdrant describes it, or null when it's gone since the listing
    private JsonNode info(String collection) throws SQLException {
        try {
            return gateway.collectionInfo(collection);
        } catch (SQLException e) {
            if (MISSING_COLLECTION.equals(e.getSQLState())) return null;

            throw e;
        }
    }

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

    /**
     * A field as text. Qdrant answers some fields with either a string or an object, such as
     * {@code optimizer_status}, which is {@code "ok"} or an object holding the error, so an
     * object becomes its JSON.
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
}
