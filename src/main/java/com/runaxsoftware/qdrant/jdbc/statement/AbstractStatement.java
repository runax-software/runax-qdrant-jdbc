package com.runaxsoftware.qdrant.jdbc.statement;

import com.runaxsoftware.qdrant.jdbc.connection.QdrantConnection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The parts of {@link Statement} that don't depend on the SQL: the settings tools apply before
 * running a query (maximum rows, fetch size, timeout), warnings, the current result, and the
 * features the driver doesn't support (batches, generated keys, named cursors).
 *
 * <p>{@link QdrantStatement} adds running the SQL.
 */
abstract class AbstractStatement implements Statement {
    private final QdrantConnection connection;
    private boolean closed;
    private boolean closeOnCompletion;
    private boolean poolable;
    private long maxRows;
    private int maxFieldSize;
    private int fetchSize;
    private int fetchDirection = ResultSet.FETCH_FORWARD;
    private int queryTimeout;
    private SQLWarning warnings;
    private ResultSet resultSet;
    private long updateCount = -1;

    // The results after the current one, for getMoreResults, when one run answers with several
    private final Deque<ResultSet> pending = new ArrayDeque<>();

    AbstractStatement(QdrantConnection connection) {
        this.connection = connection;
    }

    // Results

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return resultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        checkOpen();
        return (int) Math.min(updateCount, Integer.MAX_VALUE);
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkOpen();
        return updateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return getMoreResults(Statement.CLOSE_CURRENT_RESULT);
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        checkOpen();

        // Only a batch of searches has a next result; anything else has the one
        updateCount = -1;
        if (current == Statement.KEEP_CURRENT_RESULT) resultSet = null;
        else closeResultSet();

        resultSet = pending.poll();

        return resultSet != null;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        throw new SQLFeatureNotSupportedException("Generated keys aren't supported");
    }

    /**
     * Does nothing: a statement runs to completion on the caller's thread, and SQL that reads a
     * collection doesn't run yet, so there's never a request to stop. Tools such as DataGrip call
     * this from another thread when the user stops a query, often after the statement has finished
     * and been closed, so it doesn't throw once the statement or its connection is closed either.
     */
    @Override
    public void cancel() {
    }

    // Batches

    @Override
    public void addBatch(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException("Batches aren't supported");
    }

    @Override
    public void clearBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException("Batches aren't supported");
    }

    @Override
    public int[] executeBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException("Batches aren't supported");
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException("Batches aren't supported");
    }

    // Settings

    @Override
    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return maxFieldSize;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        checkOpen();

        if (max < 0) {
            throw new SQLException("The maximum field size can't be negative", "HY024");
        }

        maxFieldSize = max;
    }

    @Override
    public int getMaxRows() throws SQLException {
        checkOpen();
        return (int) Math.min(maxRows, Integer.MAX_VALUE);
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        setLargeMaxRows(max);
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        checkOpen();
        return maxRows;
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        checkOpen();

        if (max < 0) {
            throw new SQLException("The maximum number of rows can't be negative", "HY024");
        }

        maxRows = max;
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkOpen();
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        checkOpen();
        return queryTimeout;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        checkOpen();

        if (seconds < 0) {
            throw new SQLException("The query timeout can't be negative", "HY024");
        }

        queryTimeout = seconds;
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException("Named cursors aren't supported");
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();

        if (direction != ResultSet.FETCH_FORWARD && direction != ResultSet.FETCH_REVERSE
            && direction != ResultSet.FETCH_UNKNOWN) {
            throw new SQLException("Unknown fetch direction " + direction, "HY024");
        }

        fetchDirection = direction;
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return fetchDirection;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();

        if (rows < 0) {
            throw new SQLException("The fetch size can't be negative", "HY024");
        }

        fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        checkOpen();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() throws SQLException {
        checkOpen();
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        checkOpen();
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        checkOpen();
        this.poolable = poolable;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        checkOpen();
        return poolable;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        checkOpen();
        closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        checkOpen();
        return closeOnCompletion;
    }

    // Warnings

    /**
     * Adds a warning for the statement to report, after any it already has: a result that's
     * correct but not the whole answer, such as a read the driver bounded itself.
     */
    protected void warn(String message) {
        SQLWarning warning = new SQLWarning(message, "01000");

        if (warnings == null) {
            warnings = warning;
        } else {
            warnings.setNextWarning(warning);
        }
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings = null;
    }

    // Connection and closing

    @Override
    public QdrantConnection getConnection() throws SQLException {
        checkOpen();
        return connection;
    }

    @Override
    public void close() throws SQLException {
        if (!closed) closeResults();
        closed = true;
    }

    /**
     * Whether the statement is closed: closed itself, or through its connection, since closing a
     * connection closes its statements.
     */
    @Override
    public boolean isClosed() {
        return closed || connection.isClosed();
    }

    // Wrapper

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }

        throw new SQLException("Not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }

    /**
     * Keeps the result of the latest run, for {@link #getResultSet()}.
     */
    void setResultSet(ResultSet resultSet) {
        this.resultSet = resultSet;
    }

    /**
     * Keeps the count of the latest run, for {@link #getUpdateCount()}; -1 when it returned rows.
     */
    void setUpdateCount(long updateCount) {
        this.updateCount = updateCount;
    }

    /**
     * Keeps the results that follow the current one, in order, for {@link #getMoreResults()}.
     */
    void setPendingResults(List<ResultSet> results) {
        pending.clear();
        pending.addAll(results);
    }

    /**
     * Closes the current result, as getMoreResults does before it moves on to the next.
     */
    void closeResultSet() throws SQLException {
        ResultSet previous = resultSet;
        resultSet = null;
        if (previous != null) previous.close();
    }

    /**
     * Closes the results of the previous run, the current one and any not reached yet, as every
     * JDBC run does before it starts.
     */
    void closeResults() throws SQLException {
        closeResultSet();

        while (!pending.isEmpty()) pending.poll().close();
    }

    void checkOpen() throws SQLException {
        if (closed) throw new SQLException("The statement is closed", "HY010");
        connection.checkOpen();
    }
}
