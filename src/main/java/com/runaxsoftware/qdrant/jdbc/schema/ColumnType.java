package com.runaxsoftware.qdrant.jdbc.schema;

import java.sql.Types;

/**
 * The SQL types the driver reports for collection columns. JSON values and vectors are text
 * columns with their own type names, so any JDBC client can show them.
 */
public enum ColumnType {
    /**
     * Strings, including keyword, full-text, and UUID payload fields, and point IDs.
     */
    TEXT(Types.VARCHAR, "VARCHAR"),

    /**
     * Whole numbers.
     */
    INTEGER(Types.BIGINT, "BIGINT"),

    /**
     * Floating-point numbers.
     */
    FLOAT(Types.DOUBLE, "DOUBLE"),

    /**
     * Booleans.
     */
    BOOLEAN(Types.BOOLEAN, "BOOLEAN"),

    /**
     * Date-times, from datetime payload indexes.
     */
    DATETIME(Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE"),

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

    ColumnType(int sqlType, String typeName) {
        this.sqlType = sqlType;
        this.typeName = typeName;
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
}
