package com.runaxsoftware.qdrant.jdbc.schema;

/**
 * One column of a collection, as the driver presents it.
 *
 * @param name    the column name
 * @param kind    where the values come from
 * @param type    the SQL type
 * @param size    a vector's dimensions, or 0 for other columns
 * @param source  the payload path for payload fields or the Qdrant vector name for vectors
 *                (empty for an unnamed vector); {@code null} for the ID and payload columns
 * @param remarks a description shown as the column comment
 */
public record ColumnDefinition(
    String name,
    ColumnKind kind,
    ColumnType type,
    int size,
    String source,
    String remarks) {

    /**
     * Whether the column can hold nulls. Only the point ID is always present.
     */
    public boolean nullable() {
        return kind != ColumnKind.ID;
    }

    // in ColumnDefinition

    /**
     * The point ID column, {@code _id}.
     */
    static ColumnDefinition id() {
        return new ColumnDefinition(DriverColumns.ID.columnName(), ColumnKind.ID, ColumnType.TEXT, 0,
            null, DriverColumns.ID.remarks());
    }

    /**
     * The whole-payload column, {@code _payload}.
     */
    static ColumnDefinition payload() {
        return new ColumnDefinition(DriverColumns.PAYLOAD.columnName(), ColumnKind.PAYLOAD,
            ColumnType.JSON, 0, null, DriverColumns.PAYLOAD.remarks());
    }

    /**
     * A column for one payload field, named after its path, such as {@code specs.color}.
     */
    static ColumnDefinition payloadField(String path, ColumnType type, String remarks) {
        return new ColumnDefinition(path, ColumnKind.PAYLOAD_FIELD, type, 0, path, remarks);
    }
}
