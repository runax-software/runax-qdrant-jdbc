package com.runaxsoftware.qdrant.jdbc.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.client.CollectionInfo;
import com.runaxsoftware.qdrant.jdbc.client.Point;
import com.runaxsoftware.qdrant.jdbc.client.VectorField;
import com.runaxsoftware.qdrant.jdbc.client.VectorKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Works out a collection's columns from its configuration and a sample of its points.
 *
 * <p>Columns come in this order: {@code id}; one column per payload field, sorted by name;
 * {@code payload}, the whole payload as JSON; and one column per vector. Payload fields are
 * every top-level key in the sample plus every indexed path, so an indexed nested field such
 * as {@code specs.color} gets a column of its own. A field's type comes from its payload index
 * when it has one, checked against the sampled values; objects, arrays, and fields with mixed
 * value types become JSON. A payload field named like a reserved column ({@code id},
 * {@code payload}, or a vector) gets no column of its own; its values stay in {@code payload}.
 */
public final class SchemaInference {

    /**
     * The point ID column.
     */
    public static final String ID_COLUMN = "id";

    /**
     * The whole-payload column.
     */
    public static final String PAYLOAD_COLUMN = "payload";

    /**
     * The column for a collection's single unnamed vector.
     */
    public static final String UNNAMED_VECTOR_COLUMN = "vector";

    private SchemaInference() {
    }

    /**
     * @param collection the collection's configuration and payload indexes
     * @param sample     points read with their payloads, used to find fields and their types
     */
    public static TableSchema infer(CollectionInfo collection, List<Point> sample) {
        List<ColumnDefinition> vectors = vectorColumns(collection.vectors());
        Set<String> reserved = new HashSet<>();

        reserved.add(ID_COLUMN);
        reserved.add(PAYLOAD_COLUMN);
        vectors.forEach(vector -> reserved.add(vector.name()));

        List<ColumnDefinition> columns = new ArrayList<>();
        columns.add(new ColumnDefinition(ID_COLUMN, ColumnKind.ID, ColumnType.TEXT, 0, null,
            "Point ID: an unsigned integer or a UUID"));

        for (Map.Entry<String, Field> field : payloadFields(collection, sample).entrySet()) {
            if (!reserved.contains(field.getKey())) {
                columns.add(payloadColumn(field.getKey(), field.getValue()));
            }
        }

        columns.add(new ColumnDefinition(PAYLOAD_COLUMN, ColumnKind.PAYLOAD, ColumnType.JSON, 0,
            null, "The whole payload as JSON"));
        columns.addAll(vectors);

        return new TableSchema(collection, List.copyOf(columns));
    }

    private static Map<String, Field> payloadFields(CollectionInfo collection, List<Point> sample) {
        Map<String, Field> fields = new TreeMap<>();

        for (Point point : sample) {
            if (point.payload() != null) {
                for (Map.Entry<String, JsonNode> entry : point.payload().properties()) {
                    fields.computeIfAbsent(entry.getKey(), key -> new Field())
                        .observe(entry.getValue());
                }
            }
        }

        for (Map.Entry<String, String> index : collection.payloadIndexes().entrySet()) {
            String path = index.getKey();
            Field field = fields.computeIfAbsent(path, key -> new Field());
            field.indexType = index.getValue();

            if (isNested(path)) {
                for (Point point : sample) {
                    field.observe(valueAt(point.payload(), path));
                }
            }
        }

        return fields;
    }

    private static ColumnDefinition payloadColumn(String path, Field field) {
        String remarks = (isNested(path) ? "Nested payload field" : "Payload field")
            + (field.indexType == null ? " (not indexed)" : " (" + field.indexType + " index)");

        return new ColumnDefinition(path, ColumnKind.PAYLOAD_FIELD,
            typeOf(field.indexType, field.kinds), 0, path, remarks);
    }

    private static List<ColumnDefinition> vectorColumns(List<VectorField> vectors) {
        List<VectorField> ordered = new ArrayList<>(vectors);
        ordered.sort(Comparator.comparing((VectorField vector) -> vector.kind() == VectorKind.SPARSE)
            .thenComparing(VectorField::name));
        List<ColumnDefinition> columns = new ArrayList<>();

        for (VectorField vector : ordered) {
            String name = vector.isUnnamed() ? UNNAMED_VECTOR_COLUMN : vector.name();

            if (name.equals(ID_COLUMN) || name.equals(PAYLOAD_COLUMN)) {
                name = name + "_vector";
            }

            columns.add(new ColumnDefinition(name, ColumnKind.VECTOR, vectorType(vector.kind()),
                vector.size(), vector.name(), vectorRemarks(vector)));
        }

        return columns;
    }

    private static ColumnType vectorType(VectorKind kind) {
        return switch (kind) {
            case MULTIVECTOR -> ColumnType.MULTIVECTOR;
            case SPARSE -> ColumnType.SPARSE_VECTOR;
            default -> ColumnType.VECTOR;
        };
    }

    private static String vectorRemarks(VectorField vector) {
        return switch (vector.kind()) {
            case MULTIVECTOR -> "Multivector of " + vector.size() + "-dimension vectors, "
                + vector.distance() + " distance";
            case SPARSE -> "Sparse vector";
            default -> "Dense vector, " + vector.size() + " dimensions, "
                + vector.distance() + " distance";
        };
    }

    /**
     * Picks a payload field's type from its index type and the kinds of values sampled.
     */
    static ColumnType typeOf(String indexType, Set<ValueKind> kinds) {
        if (kinds.contains(ValueKind.OBJECT) || kinds.contains(ValueKind.ARRAY)) {
            return ColumnType.JSON;
        }

        ColumnType indexed = indexType == null ? null : fromIndex(indexType);
        if (indexed == null) {
            return fromKinds(kinds);
        }

        return switch (indexed) {
            case TEXT, DATETIME -> onlyOf(kinds, ValueKind.STRING) ? indexed : ColumnType.JSON;
            case INTEGER -> {
                if (onlyOf(kinds, ValueKind.INTEGER)) {
                    yield ColumnType.INTEGER;
                }
                yield onlyOf(kinds, ValueKind.INTEGER, ValueKind.FLOAT)
                    ? ColumnType.FLOAT
                    : ColumnType.JSON;
            }
            case FLOAT -> onlyOf(kinds, ValueKind.INTEGER, ValueKind.FLOAT)
                ? ColumnType.FLOAT
                : ColumnType.JSON;
            case BOOLEAN -> onlyOf(kinds, ValueKind.BOOLEAN) ? ColumnType.BOOLEAN : ColumnType.JSON;
            default -> ColumnType.JSON;
        };
    }

    private static ColumnType fromIndex(String indexType) {
        return switch (indexType) {
            case "keyword", "text", "uuid" -> ColumnType.TEXT;
            case "integer" -> ColumnType.INTEGER;
            case "float" -> ColumnType.FLOAT;
            case "bool" -> ColumnType.BOOLEAN;
            case "datetime" -> ColumnType.DATETIME;
            case "geo" -> ColumnType.JSON;
            default -> null;
        };
    }

    private static ColumnType fromKinds(Set<ValueKind> kinds) {
        if (kinds.isEmpty() || onlyOf(kinds, ValueKind.STRING)) {
            return ColumnType.TEXT;
        }

        if (onlyOf(kinds, ValueKind.INTEGER)) {
            return ColumnType.INTEGER;
        }

        if (onlyOf(kinds, ValueKind.INTEGER, ValueKind.FLOAT)) {
            return ColumnType.FLOAT;
        }

        if (onlyOf(kinds, ValueKind.BOOLEAN)) {
            return ColumnType.BOOLEAN;
        }

        return ColumnType.JSON;
    }

    private static boolean onlyOf(Set<ValueKind> kinds, ValueKind first, ValueKind... rest) {
        return EnumSet.of(first, rest).containsAll(kinds);
    }

    private static boolean isNested(String path) {
        return path.indexOf('.') >= 0 || path.indexOf('[') >= 0;
    }

    /**
     * Follows a dotted path such as {@code specs.color}; paths into arrays aren't followed.
     */
    private static JsonNode valueAt(JsonNode payload, String path) {
        if (payload == null || path.indexOf('[') >= 0) {
            return null;
        }

        JsonNode node = payload;
        for (String key : path.split("\\.", -1)) {
            node = node.get(key);

            if (node == null) {
                return null;
            }
        }

        return node;
    }

    /**
     * The kinds of JSON values a payload field can hold.
     */
    enum ValueKind {
        STRING, INTEGER, FLOAT, BOOLEAN, OBJECT, ARRAY
    }

    private static final class Field {
        private final Set<ValueKind> kinds = EnumSet.noneOf(ValueKind.class);
        private String indexType;

        void observe(JsonNode value) {
            if (value == null || value.isNull() || value.isMissingNode()) {
                return;
            }

            if (value.isTextual()) {
                kinds.add(ValueKind.STRING);
            } else if (value.isIntegralNumber()) {
                kinds.add(ValueKind.INTEGER);
            } else if (value.isNumber()) {
                kinds.add(ValueKind.FLOAT);
            } else if (value.isBoolean()) {
                kinds.add(ValueKind.BOOLEAN);
            } else if (value.isObject()) {
                kinds.add(ValueKind.OBJECT);
            } else if (value.isArray()) {
                kinds.add(ValueKind.ARRAY);
            }
        }
    }
}
