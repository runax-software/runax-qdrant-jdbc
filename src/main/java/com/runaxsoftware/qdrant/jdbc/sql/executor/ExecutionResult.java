package com.runaxsoftware.qdrant.jdbc.sql.executor;

import java.sql.ResultSet;

/**
 * What running a plan produced: rows, or a count of points written or deleted. The two match
 * what {@link java.sql.Statement#getResultSet()} and
 * {@link java.sql.Statement#getLargeUpdateCount()} return.
 *
 * @param resultSet   the rows, or {@code null} when the statement changed points
 * @param updateCount the number of points written or deleted, or -1 when there are rows
 */
public record ExecutionResult(ResultSet resultSet, long updateCount) {

    public ExecutionResult {
        if ((resultSet == null) == (updateCount == -1)) {
            throw new IllegalArgumentException(
                "Expected either a result set or an update count, not both or neither");
        }
        if (updateCount < -1) {
            throw new IllegalArgumentException("The update count can't be negative");
        }
    }

    /**
     * A result with rows, from a SELECT.
     */
    public static ExecutionResult rows(ResultSet resultSet) {
        return new ExecutionResult(resultSet, -1);
    }

    /**
     * A result without rows, from an INSERT, UPDATE, or DELETE.
     */
    public static ExecutionResult updated(long count) {
        return new ExecutionResult(null, count);
    }

    /**
     * Whether there are rows, as {@link java.sql.Statement#execute(String)} returns.
     */
    public boolean hasResultSet() {
        return resultSet != null;
    }
}
