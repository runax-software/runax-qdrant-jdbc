package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.schema.DriverColumns;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ColumnSpec;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateCollectionPlan.PayloadIndex;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans a CREATE TABLE: its vector columns become the collection's vectors, and its indexed
 * columns its payload indexes. Qdrant's payload has no schema, so a payload column is only
 * worth declaring to index it.
 */
final class CreatePlanner {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    // The unnamed vector, which a collection has instead of named ones
    private static final String UNNAMED = DriverColumns.VECTOR.columnName();

    private static final Map<String, String> DISTANCES = Map.of(
        "COSINE", "Cosine",
        "DOT", "Dot",
        "EUCLID", "Euclid",
        "EUCLIDEAN", "Euclid",
        "MANHATTAN", "Manhattan");

    // Qdrant's index types, under their own names and the SQL types that mean the same
    private static final Map<String, String> INDEXES = Map.ofEntries(
        Map.entry("KEYWORD", "keyword"),
        Map.entry("VARCHAR", "keyword"),
        Map.entry("INTEGER", "integer"),
        Map.entry("BIGINT", "integer"),
        Map.entry("FLOAT", "float"),
        Map.entry("DOUBLE", "float"),
        Map.entry("BOOL", "bool"),
        Map.entry("BOOLEAN", "bool"),
        Map.entry("DATETIME", "datetime"),
        Map.entry("TIMESTAMP", "datetime"),
        Map.entry("TEXT", "text"),
        Map.entry("UUID", "uuid"),
        Map.entry("GEO", "geo"));

    static CreateCollectionPlan plan(CreateTableStatement statement, String collection)
        throws SQLException {
        ObjectNode dense = JSON.objectNode();
        ObjectNode sparse = JSON.objectNode();
        ObjectNode unnamed = null;
        var indexes = new ArrayList<PayloadIndex>();
        Set<String> names = new HashSet<>();

        for (ColumnSpec column : statement.columns()) {
            if (!names.add(column.name())) {
                throw new SQLSyntaxErrorException("Column '" + column.name() + "' is named twice",
                    "42000");
            }

            switch (column.type()) {
                case "VECTOR", "MULTIVECTOR" -> {
                    ObjectNode vector = dense(column, collection);

                    if (column.name().equals(UNNAMED)) unnamed = vector;
                    else dense.set(column.name(), vector);
                }
                case "SPARSE_VECTOR" -> sparse.set(column.name(), sparse(column));
                default -> indexes.add(index(column));
            }
        }

        if (unnamed != null && (!dense.isEmpty() || !sparse.isEmpty())) {
            throw new SQLFeatureNotSupportedException("A collection has either one unnamed "
                + "vector, the column " + UNNAMED + ", or named vectors: name every vector "
                + "column to have several");
        }

        ObjectNode config = JSON.objectNode();
        if (unnamed != null) config.set("vectors", unnamed);
        if (!dense.isEmpty()) config.set("vectors", dense);
        if (!sparse.isEmpty()) config.set("sparse_vectors", sparse);

        return new CreateCollectionPlan(collection, statement.ifNotExists(), config, indexes);
    }

    // A dense vector or multivector: its size, and a distance that defaults to cosine
    static ObjectNode dense(ColumnSpec column, String collection) throws SQLException {
        if (column.size() == null || column.size() <= 0) {
            throw new SQLSyntaxErrorException("Vector '" + column.name() + "' needs its "
                + "dimensions, as " + column.type() + "(384)", "42000");
        }
        if (column.modifier() != null || column.index()) {
            throw new SQLSyntaxErrorException("Vector '" + column.name() + "' takes only its "
                + "dimensions and a DISTANCE", "42000");
        }

        String distance = column.distance() == null ? "Cosine" : DISTANCES.get(column.distance());
        if (distance == null) {
            throw new SQLSyntaxErrorException("Unknown distance '" + column.distance() + "' for "
                + "vector '" + column.name() + "' of " + collection
                + ": Qdrant has COSINE, DOT, EUCLID and MANHATTAN", "42000");
        }

        ObjectNode vector = JSON.objectNode().put("size", column.size()).put("distance", distance);

        if (column.type().equals("MULTIVECTOR")) {
            vector.set("multivector_config", JSON.objectNode().put("comparator", "max_sim"));
        }

        return vector;
    }

    // A sparse vector, whose size and distance Qdrant fixes itself
    static ObjectNode sparse(ColumnSpec column) throws SQLException {
        if (column.name().equals(UNNAMED)) {
            throw new SQLFeatureNotSupportedException("A sparse vector needs a name of its own: "
                + UNNAMED + " is the unnamed dense vector");
        }
        if (column.size() != null || column.distance() != null || column.index()) {
            throw new SQLSyntaxErrorException("Sparse vector '" + column.name() + "' takes only "
                + "a MODIFIER, as MODIFIER IDF", "42000");
        }

        ObjectNode vector = JSON.objectNode();

        if (column.modifier() == null || column.modifier().equals("NONE")) {
            return vector;
        }
        if (column.modifier().equals("IDF")) {
            return vector.put("modifier", "idf");
        }

        throw new SQLSyntaxErrorException("Unknown modifier '" + column.modifier() + "' for "
            + "sparse vector '" + column.name() + "': Qdrant has IDF and NONE", "42000");
    }

    /**
     * Qdrant's name for an index type, given under its own name or an SQL type that means the
     * same, or {@code null} when it's neither.
     */
    static String indexType(String type) {
        return INDEXES.get(type);
    }

    // A payload column, which only an index makes worth declaring
    private static PayloadIndex index(ColumnSpec column) throws SQLException {
        String type = INDEXES.get(column.type());

        if (type == null) {
            throw new SQLFeatureNotSupportedException("Unknown column type '" + column.type()
                + "' for '" + column.name() + "': vectors are VECTOR(n), MULTIVECTOR(n) and "
                + "SPARSE_VECTOR, and indexed payload fields KEYWORD, INTEGER, FLOAT, BOOL, "
                + "DATETIME, TEXT, UUID or GEO, followed by INDEX");
        }
        if (column.name().startsWith("_")) {
            throw new SQLSyntaxErrorException("Column '" + column.name() + "' starts with _, "
                + "which the driver keeps for its own columns", "42000");
        }
        if (!column.index()) {
            throw new SQLFeatureNotSupportedException("Payload fields need no declaring, since "
                + "Qdrant's payload has no schema: write '" + column.name() + " " + column.type()
                + " INDEX' to index it, or leave it out");
        }
        if (column.size() != null || column.distance() != null || column.modifier() != null) {
            throw new SQLSyntaxErrorException("Payload field '" + column.name() + "' takes only "
                + "its type and INDEX", "42000");
        }

        return new PayloadIndex(column.name(), type);
    }

    private CreatePlanner() {
    }
}
