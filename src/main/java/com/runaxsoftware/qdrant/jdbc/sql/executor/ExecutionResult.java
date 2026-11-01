package com.runaxsoftware.qdrant.jdbc.sql.executor;

import java.sql.ResultSet;
import java.util.List;

/**
 * What running a plan produced: rows, or a count of points written or deleted. The two match
 * what {@link java.sql.Statement#getResultSet()} and
 * {@link java.sql.Statement#getLargeUpdateCount()} return.
 *
 * @param resultSet   the rows, or {@code null} when the statement changed points
 * @param updateCount the number of points written or deleted, or -1 when there are rows
 * @param warning     what the statement should warn about, or {@code null} for nothing
 * @param keys        the IDs of the points an INSERT added, as text, for
 *                    {@link java.sql.Statement#getGeneratedKeys()}; empty for anything else
 */
public record ExecutionResult(ResultSet resultSet, long updateCount, String warning,
                              List<String> keys) {

    public ExecutionResult {
        keys = List.copyOf(keys);

        if ((resultSet == null) == (updateCount == -1)) {
            throw new IllegalArgumentException(
                "Expected either a result set or an update count, not both or neither");
        }
        if (updateCount < -1) {
            throw new IllegalArgumentException("The update count can't be negative");
        }
    }

    /**
     * A result that added no points.
     */
    public ExecutionResult(ResultSet resultSet, long updateCount, String warning) {
        this(resultSet, updateCount, warning, List.of());
    }

    /**
     * A result with rows, from a SELECT.
     */
    public static ExecutionResult rows(ResultSet resultSet) {
        return rows(resultSet, null);
    }

    /**
     * A result with rows that the statement should warn about, such as a read the driver
     * bounded itself.
     *
     * @param warning what to warn, or {@code null} for nothing
     */
    public static ExecutionResult rows(ResultSet resultSet, String warning) {
        return new ExecutionResult(resultSet, -1, warning);
    }

    /**
     * A result without rows, from an UPDATE or DELETE.
     */
    public static ExecutionResult updated(long count) {
        return new ExecutionResult(null, count, null);
    }

    /**
     * A result without rows from an INSERT, with the IDs of the points it added.
     */
    public static ExecutionResult inserted(List<String> keys) {
        return new ExecutionResult(null, keys.size(), null, keys);
    }

    /**
     * Whether there are rows, as {@link java.sql.Statement#execute(String)} returns.
     */
    public boolean hasResultSet() {
        return resultSet != null;
    }
}
