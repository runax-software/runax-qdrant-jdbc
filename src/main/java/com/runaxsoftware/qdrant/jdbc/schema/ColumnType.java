package com.runaxsoftware.qdrant.jdbc.schema;

import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;

import java.sql.Types;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The SQL types the driver reports for collection columns. JSON values and vectors are text
 * columns with their own type names, so any JDBC client can show them.
 */
public enum ColumnType {
    /**
     * Strings, including keyword, full-text, and UUID payload fields, and point IDs.
     */
    TEXT(Types.VARCHAR, "VARCHAR", ValueKind.STRING),

    /**
     * Whole numbers.
     */
    INTEGER(Types.BIGINT, "BIGINT", ValueKind.INTEGER),

    /**
     * Floating-point numbers, which whole numbers fit too.
     */
    FLOAT(Types.DOUBLE, "DOUBLE", ValueKind.INTEGER, ValueKind.FLOAT),

    /**
     * Booleans.
     */
    BOOLEAN(Types.BOOLEAN, "BOOLEAN", ValueKind.BOOLEAN),

    /**
     * Date-times, from datetime payload indexes. Qdrant stores them as strings.
     */
    DATETIME(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE", ValueKind.STRING),

    /**
     * Objects, arrays, and fields whose values have mixed types, as JSON text.
     */
    JSON(Types.VARCHAR, "JSON"),

    /**
     * A dense vector, as a JSON array.
     */
    VECTOR(Types.VARCHAR, "VECTOR"),

    /**
     * A multivector, as a JSON array of arrays.
     */
    MULTIVECTOR(Types.VARCHAR, "MULTIVECTOR"),

    /**
     * A sparse vector, as a JSON object of indices and values.
     */
    SPARSE_VECTOR(Types.VARCHAR, "SPARSE_VECTOR");

    private final int sqlType;
    private final String typeName;
    private final Set<ValueKind> accepts;

    ColumnType(int sqlType, String typeName, ValueKind... accepts) {
        this.sqlType = sqlType;
        this.typeName = typeName;
        this.accepts = accepts.length == 0
            ? EnumSet.noneOf(ValueKind.class)
            : EnumSet.copyOf(Arrays.asList(accepts));
    }

    /**
     * The {@link Types} constant.
     */
    public int sqlType() {
        return sqlType;
    }

    /**
     * The type name shown to users.
     */
    public String typeName() {
        return typeName;
    }

    /**
     * Whether every sampled kind of value fits this type.
     */
    boolean accepts(Set<ValueKind> kinds) {
        return accepts.containsAll(kinds);
    }

    /**
     * The column type a payload index implies, or {@code null} when it says nothing and the
     * sampled values decide.
     */
    public static ColumnType forIndex(PayloadIndexType index) {
        if (index == null) return null;

        return switch (index) {
            case KEYWORD, TEXT, UUID -> TEXT;
            case INTEGER -> INTEGER;
            case FLOAT -> FLOAT;
            case BOOL -> BOOLEAN;
            case DATETIME -> DATETIME;
            case GEO -> JSON;
            case UNKNOWN -> null;
        };
    }

    /**
     * A payload field's type: its index's type if every sampled value fits it, the narrowest
     * type that fits the sample when it has no index, and JSON otherwise.
     */
    static ColumnType forPayloadField(ColumnType indexed, Set<ValueKind> kinds) {
        if (kinds.contains(ValueKind.OBJECT) || kinds.contains(ValueKind.ARRAY)) return JSON;

        ColumnType type = indexed != null ? indexed : narrowest(kinds);
        if (type.accepts(kinds)) return type;
        if (type == INTEGER && FLOAT.accepts(kinds)) return FLOAT;

        return JSON;
    }

    static ColumnType forVector(VectorKind kind) {
        return switch (kind) {
            case MULTIVECTOR -> ColumnType.MULTIVECTOR;
            case SPARSE -> ColumnType.SPARSE_VECTOR;
            default -> ColumnType.VECTOR;
        };
    }

    private static ColumnType narrowest(Set<ValueKind> kinds) {
        for (ColumnType type : List.of(TEXT, INTEGER, FLOAT, BOOLEAN)) if (type.accepts(kinds)) return type;
        return JSON;
    }
}
