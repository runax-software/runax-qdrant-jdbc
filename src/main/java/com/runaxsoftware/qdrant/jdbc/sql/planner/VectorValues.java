package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.embedding.Provider;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.FunctionCall;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.VectorLiteral;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.EmbedText;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The JSON a vector column is written as, from what a statement gives it: a vector written out
 * ({@code [0.1, 0.2]}), a vector as JSON text, as a tool sends a cell, or text to embed with
 * {@code embed('text')}. Shared by INSERT and UPDATE, so a vector means the same thing whether
 * a point is being added or changed.
 *
 * <p>A vector's dimensions and shape are checked against the collection here, so a typo is an
 * error from the driver rather than from Qdrant.
 */
final class VectorValues {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMBED = "embed";

    // The two arrays Qdrant takes a sparse vector as
    static final String INDICES = "indices";
    private static final String VALUES = "values";

    // The highest index a sparse vector has: Qdrant keeps them as unsigned 32-bit numbers
    private static final long MAX_SPARSE_INDEX = 0xFFFF_FFFFL;

    private VectorValues() {
    }

    /**
     * Reads one vector's value into the right map: {@code vectors} for a vector the statement
     * gives, or for text Qdrant embeds itself, and {@code embeds} for text the driver has to
     * embed before it writes. A NULL value puts nothing in either, which the caller reads as
     * "leave it out" for an INSERT and "delete it" for an UPDATE.
     *
     * @return whether the value was NULL
     */
    static boolean of(Expression value, ColumnDefinition vector, TableSchema schema,
                      Map<String, JsonNode> vectors, Map<String, EmbedText> embeds)
        throws SQLException {
        String name = vector.source();

        if (value instanceof FunctionCall call && call.is(EMBED)) {
            EmbedText embed = SelectPlanner.embed(call, vector, schema);

            // Qdrant embeds its own models as it stores the point; the driver embeds the rest
            if (embed.model().provider() == Provider.QDRANT) {
                vectors.put(name, JSON.objectNode().put("text", embed.text())
                    .put("model", embed.model().name()));
            } else {
                embeds.put(name, embed);
            }

            return false;
        }

        if (value instanceof VectorLiteral literal) {
            vectors.put(name, dense(literal.values(), vector, schema));

            return false;
        }

        Object constant = constant(value, vector.name());
        if (constant == null) {
            return true;
        }

        if (!(constant instanceof String text)) {
            throw new SQLException("Vector '" + vector.name() + "' takes a vector, as [0.1, 0.2], "
                + "or embed('text')", "22018");
        }

        vectors.put(name, written(text, vector, schema));

        return false;
    }

    /**
     * A vector written as JSON text, as a tool sends a vector cell and as a statement writes a
     * sparse vector or a multivector, which have no literal of their own: the JSON Qdrant takes
     * for the column's kind of vector, checked against it.
     */
    static JsonNode written(String text, ColumnDefinition vector, TableSchema schema)
        throws SQLException {
        JsonNode json;
        try {
            json = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            json = null;
        }

        if (vector.type() == ColumnType.SPARSE_VECTOR && json != null && json.isObject()
            && json.path(INDICES).isArray() && json.path(VALUES).isArray()
            && json.path(INDICES).size() == json.path(VALUES).size()) {
            return sparse(json, vector, schema);
        }
        if (vector.type() == ColumnType.MULTIVECTOR && json != null && json.isArray()
            && !json.isEmpty()) {
            return multivector(json, vector, schema);
        }
        if (vector.type() == ColumnType.VECTOR && json != null && json.isArray()) {
            var values = new ArrayList<Double>();
            for (JsonNode x : json) {
                if (!x.isNumber()) throw shape(vector, schema);
                values.add(x.asDouble());
            }
            return dense(values, vector, schema);
        }

        throw shape(vector, schema);
    }

    /**
     * A sparse vector's indices and values, as Qdrant takes them: each index a whole number from
     * 0 up, once. Qdrant answers anything else with an error about the request's JSON that
     * names neither the vector nor what is wrong with it.
     */
    private static JsonNode sparse(JsonNode json, ColumnDefinition vector, TableSchema schema)
        throws SQLException {
        var seen = new HashSet<Long>();

        for (JsonNode index : json.get(INDICES)) {
            if (!index.isIntegralNumber() || !index.canConvertToLong() || index.asLong() < 0
                || index.asLong() > MAX_SPARSE_INDEX) {
                throw shape(vector, schema);
            }
            if (!seen.add(index.asLong())) {
                throw new SQLException("Sparse vector '" + vector.name() + "' of " + schema.name()
                    + " takes each index once, and the value has " + index.asLong() + " twice",
                    "22018");
            }
        }
        for (JsonNode value : json.get(VALUES)) {
            if (!value.isNumber()) throw shape(vector, schema);
        }

        // Only the two arrays: Qdrant has no other field for a sparse vector
        ObjectNode sparse = JSON.objectNode();
        sparse.set(INDICES, json.get(INDICES));
        sparse.set(VALUES, json.get(VALUES));

        return sparse;
    }

    /**
     * A multivector's rows, each of the column's dimensions, every number written as a decimal.
     * Qdrant reads two rows of which the first holds whole numbers, as {@code [[1, 0], [0, 1]]},
     * as a sparse vector's indices and values, and then rejects it; {@code [[1.0, 0.0], ...]}
     * can only be rows.
     */
    private static JsonNode multivector(JsonNode json, ColumnDefinition vector, TableSchema schema)
        throws SQLException {
        ArrayNode rows = JSON.arrayNode();

        for (JsonNode row : json) {
            if (!row.isArray() || row.size() != vector.size()) {
                throw shape(vector, schema);
            }

            ArrayNode values = rows.addArray();
            for (JsonNode value : row) {
                if (!value.isNumber()) throw shape(vector, schema);
                values.add(value.asDouble());
            }
        }

        return rows;
    }

    private static JsonNode dense(List<Double> values, ColumnDefinition vector, TableSchema schema)
        throws SQLException {
        if (vector.type() != ColumnType.VECTOR) {
            throw shape(vector, schema);
        }
        if (values.size() != vector.size()) {
            throw new SQLException("Vector '" + vector.name() + "' of " + schema.name() + " has "
                + vector.size() + " dimensions, the value has " + values.size(), "22018");
        }

        ArrayNode array = JSON.arrayNode();
        values.forEach(array::add);
        return array;
    }

    // What the column's kind of vector looks like written out, for a value that isn't one
    static SQLException shape(ColumnDefinition vector, TableSchema schema) {
        String expected = switch (vector.type()) {
            case SPARSE_VECTOR -> "a sparse vector, as '{\"indices\": [1, 7], \"values\": [0.5, 0.9]}'";
            case MULTIVECTOR -> "vectors of " + vector.size() + ", as '[[0.1, 0.2], [0.3, 0.4]]'";
            default -> vector.size() + " numbers, as [0.1, 0.2]";
        };

        return new SQLException("Vector '" + vector.name() + "' of " + schema.name() + " takes "
            + expected + ", or embed('text')", "22018");
    }

    // A constant's value, or null for NULL; a placeholder reaches here only unbound
    private static Object constant(Expression value, String name) throws SQLException {
        if (value instanceof Literal literal) {
            return literal.value();
        }
        if (value instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("Parameters are given through a "
                + "PreparedStatement");
        }

        throw new SQLFeatureNotSupportedException("'" + name + "' takes a constant, a vector, or "
            + "embed('text'), not a column or another call");
    }
}
