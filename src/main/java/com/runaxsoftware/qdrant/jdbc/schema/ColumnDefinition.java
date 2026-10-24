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
}
