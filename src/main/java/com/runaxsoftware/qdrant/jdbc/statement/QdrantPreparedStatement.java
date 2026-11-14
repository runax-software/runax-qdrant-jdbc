package com.runaxsoftware.qdrant.jdbc.statement;

import com.runaxsoftware.qdrant.jdbc.connection.QdrantConnection;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parameters;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Set;

/**
 * A statement whose SQL is parsed once, with {@code ?} for values given before each run:
 * {@code SELECT _id FROM docs WHERE lang = ? AND year >= ?}. A value takes a placeholder's place
 * in the parsed statement, never in the SQL text, so it can't change what the statement means.
 * Syntax errors are reported when the statement is prepared; unknown names, when it runs, since
 * the collection's columns may change in between.
 *
 * <p>A placeholder stands for a constant: in a condition, in the select list, as the vector of a
 * similarity search, given as {@code float[]} or {@code double[]}, or as the whole number of a
 * LIMIT, an OFFSET or a {@code LIMIT n BY column}.
 */
public final class QdrantPreparedStatement extends QdrantStatement implements PreparedStatement {

    private static final Object UNSET = new Object();

    private final Statement statement;
    private final Object[] values;
    private final Set<Integer> rowCounts;
    private final List<Object[]> batch = new ArrayList<>();

    public QdrantPreparedStatement(QdrantConnection connection, String sql) throws SQLException {
        super(connection);
        this.statement = parse(sql);
        this.values = new Object[Parameters.count(statement)];
        this.rowCounts = Parameters.rowCounts(statement);

        Arrays.fill(values, UNSET);
    }

    // Running

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkOpen();
        return query(bound(values));
    }

    @Override
    public int executeUpdate() throws SQLException {
        return (int) Math.min(executeLargeUpdate(), Integer.MAX_VALUE);
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        checkOpen();
        return update(bound(values));
    }

    @Override
    public boolean execute() throws SQLException {
        checkOpen();
        return execute(bound(values));
    }

    /**
     * The plan for the statement with these values in place of its placeholders.
     */
    private Plan bound(Object[] given) throws SQLException {
        var expressions = new Expression[given.length];

        for (int i = 0; i < given.length; i++) {
            if (given[i] == UNSET) {
                throw new SQLException("No value given for parameter " + (i + 1), "07001");
            }

            expressions[i] = ParameterValues.of(given[i]);
        }

        return plan(Parameters.bind(statement, index -> expressions[index - 1]));
    }

    // The SQL was given when the statement was prepared, so none is taken now

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw sqlGiven();
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        throw sqlGiven();
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        throw sqlGiven();
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw sqlGiven();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw sqlGiven();
    }

    private static SQLException sqlGiven() {
        return new SQLException("A prepared statement runs the SQL it was prepared with: use "
            + "executeQuery(), executeUpdate() or execute() without SQL", "HY000");
    }

    // Batches: the same statement with several sets of values, as for deleting many points

    @Override
    public void addBatch() throws SQLException {
        checkOpen();
        batch.add(values.clone());
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        batch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        long[] counts = executeLargeBatch();
        int[] result = new int[counts.length];

        for (int i = 0; i < counts.length; i++) {
            result[i] = (int) Math.min(counts[i], Integer.MAX_VALUE);
        }

        return result;
    }

    /**
     * Runs each set of values in turn. Qdrant has no transactions, so the sets that ran before
     * a failure stay applied; the exception's counts say which.
     */
    @Override
    public long[] executeLargeBatch() throws SQLException {
        checkOpen();
        var counts = new long[batch.size()];

        try {
            for (int i = 0; i < batch.size(); i++) {
                try {
                    counts[i] = update(bound(batch.get(i)));
                } catch (SQLException e) {
                    long[] done = Arrays.copyOf(counts, i);
                    throw new BatchUpdateException(e.getMessage(), e.getSQLState(),
                        e.getErrorCode(), done, e);
                }
            }
        } finally {
            batch.clear();
        }

        return counts;
    }

    // Values

    @Override
    public void clearParameters() throws SQLException {
        checkOpen();
        Arrays.fill(values, UNSET);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        set(parameterIndex, null);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        set(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        set(parameterIndex, value);
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        set(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
        throws SQLException {
        set(parameterIndex, x);
    }

    /**
     * Keeps a value for a placeholder. It's converted once now, so a type no placeholder can
     * take fails at the call that gave it rather than when the statement runs.
     */
    private void set(int parameterIndex, Object value) throws SQLException {
        checkOpen();
        checkIndex(parameterIndex, values.length);

        ParameterValues.of(value);
        values[parameterIndex - 1] = value;
    }

    static void checkIndex(int parameterIndex, int count) throws SQLException {
        if (parameterIndex < 1 || parameterIndex > count) {
            throw new SQLException("Parameter " + parameterIndex + " is out of range: the "
                + "statement has " + count + (count == 1 ? " parameter" : " parameters"), "07009");
        }
    }

    // Describing

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        checkOpen();
        return new QdrantParameterMetaData(values.length, rowCounts);
    }

    /**
     * Returns {@code null}, which JDBC allows: the columns depend on the values, such as a
     * search's vector, so they're known once the statement runs.
     */
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return null;
    }

    // Values Qdrant has no use for

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        throw unsupported("Bytes");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        throw unsupported("REF values");
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        throw unsupported("BLOBs");
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        throw unsupported("CLOBs");
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        throw unsupported("SQL arrays (give a vector as float[] or double[])");
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        throw unsupported("URLs");
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        throw unsupported("Row IDs");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        throw unsupported("NCLOBs");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw unsupported("CLOBs");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        throw unsupported("BLOBs");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw unsupported("NCLOBs");
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        throw unsupported("XML values");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        throw unsupported("Streams");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported("CLOBs");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        throw unsupported("BLOBs");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        throw unsupported("NCLOBs");
    }

    private static SQLFeatureNotSupportedException unsupported(String what) {
        return new SQLFeatureNotSupportedException(what + " aren't supported as parameters");
    }
}
