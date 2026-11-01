package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;

/**
 * One column of a GROUP BY's result: either the field the points are grouped by or how many
 * points each group holds.
 *
 * @param name     the column name, which an alias replaces
 * @param type     the SQL type: the grouped field's own type, or a whole number for a count
 * @param counting whether the column holds the count rather than the grouped value
 */
public record FacetColumn(String name, ColumnType type, boolean counting) {

    /**
     * Whether the column can hold nulls. A count never is; a grouped value never is either,
     * since Qdrant counts the values its points hold and a point without the field is in no
     * group.
     */
    public boolean nullable() {
        return false;
    }
}
