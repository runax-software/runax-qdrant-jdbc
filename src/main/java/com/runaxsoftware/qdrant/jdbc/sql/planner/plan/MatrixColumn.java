package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import java.sql.Types;
import java.util.Arrays;
import java.util.Optional;

/**
 * The columns a distance matrix has: a pair of points and how close they are, and where each
 * point sits among the sampled ones.
 */
public enum MatrixColumn {

    /** The sampled point's ID, as text, like {@code _id}. */
    A("_a", Types.VARCHAR, false),

    /** Its neighbour's ID. */
    B("_b", Types.VARCHAR, false),

    /** How close the two are, by the vector's distance. */
    SCORE("_score", Types.DOUBLE, false),

    /** The point's position among the sampled points, from 0: the cell's row in the matrix. */
    ROW("_row", Types.BIGINT, true),

    /** The neighbour's position among them: the cell's column. */
    COL("_col", Types.BIGINT, true);

    private final String columnName;
    private final int sqlType;
    private final boolean position;

    MatrixColumn(String columnName, int sqlType, boolean position) {
        this.columnName = columnName;
        this.sqlType = sqlType;
        this.position = position;
    }

    public String columnName() {
        return columnName;
    }

    /**
     * The {@link Types} constant of the column.
     */
    public int sqlType() {
        return sqlType;
    }

    /**
     * Whether the column is a position in the matrix, which Qdrant answers only when the matrix
     * is asked for as offsets.
     */
    public boolean position() {
        return position;
    }

    /**
     * The column with this name, or empty when the matrix has none.
     */
    public static Optional<MatrixColumn> named(String name) {
        return Arrays.stream(values()).filter(column -> column.columnName.equals(name))
            .findFirst();
    }
}
