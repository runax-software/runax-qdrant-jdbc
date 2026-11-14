package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadPath;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;

import java.sql.ResultSetMetaData;
import java.util.List;

/**
 * How a point becomes a row: which columns a plan's result set has, and what each of them
 * holds. A scroll and a search return the same points in the same shapes, so both read them
 * the same way; only a search has a score.
 */
final class PointValues {

    private PointValues() {
    }

    /**
     * The result set's columns for a plan's columns.
     *
     * @param collection the collection the columns come from, which JDBC reports per column
     */
    static List<ResultColumn> resultColumns(List<ColumnDefinition> columns, String collection) {
        return columns.stream()
            .map(x -> new ResultColumn(
                x.name(),
                x.type().sqlType(),
                x.type().typeName(),
                collection,
                x.size(),
                x.nullable()
                    ? ResultSetMetaData.columnNullable
                    : ResultSetMetaData.columnNoNulls))
            .toList();
    }

    /**
     * One column's value for one point.
     *
     * @param score the score the search gave the point, or {@code null} for a scroll, which
     *              ranks nothing and whose plans hold no score column
     */
    static Object of(Point point, Double score, ColumnDefinition column) {
        return switch (column.kind()) {
            case ID -> point.id().asText();
            case PAYLOAD -> point.payload() == null ? null : point.payload().toString();
            case PAYLOAD_FIELD -> value(PayloadPath.valueAt(point.payload(), column.source()), column);
            case VECTOR -> vector(point, column.source());
            case SCORE -> score;
        };
    }

    private static Object value(JsonNode node, ColumnDefinition column) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (column.type() == ColumnType.JSON) {
            return node.toString();
        }

        return node.isIntegralNumber() && node.canConvertToLong()
            ? node.longValue()
            : node.isNumber() ? node.doubleValue()
            : node.isBoolean() ? node.booleanValue()
            : node.isTextual() ? node.asText()   // the text itself, without JSON's quotes
            : node.toString();
    }

    /**
     * One vector of a point, as JSON text.
     *
     * @param name the Qdrant vector's name, empty for a collection's single unnamed vector
     */
    private static Object vector(Point point, String name) {
        JsonNode vectors = point.vector();

        if (vectors == null) {
            return null;
        }

        // The unnamed vector is the answer itself, unless the collection has named ones beside
        // it: then Qdrant answers with an object, and the unnamed one is under the empty name
        JsonNode vector = name.isEmpty() && !vectors.isObject() ? vectors : vectors.get(name);

        return vector == null || vector.isNull() ? null : vector.toString();
    }
}
