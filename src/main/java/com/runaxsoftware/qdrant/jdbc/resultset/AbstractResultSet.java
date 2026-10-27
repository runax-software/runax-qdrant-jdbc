package com.runaxsoftware.qdrant.jdbc.resultset;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * Base for the driver's result sets, which are all read-only and forward-only.
 *
 * <p>Subclasses supply the rows through {@link #advance()}, {@link #hasNextRow()}, and
 * {@link #currentValue(int)}; this class handles the cursor and type conversions, and
 * {@link ReadOnlyResultSet} refuses scrolling and updates.
 */
public abstract class AbstractResultSet extends ReadOnlyResultSet {

    private final List<ResultColumn> columns;
    private final Statement statement;
    private boolean closed;
    private boolean onRow;
    private boolean afterLast;
    private int row;
    private boolean wasNull;
    private int fetchSize;
    private SQLWarning warnings;

    /**
     * @param columns   the result's columns, in order
     * @param statement the statement that produced the result, or {@code null} for metadata
     */
    protected AbstractResultSet(List<ResultColumn> columns, Statement statement) {
        this.columns = List.copyOf(columns);
        this.statement = statement;
    }

    /**
     * Moves to the next row. Returns {@code false} when there are no more rows.
     */
    protected abstract boolean advance() throws SQLException;

    /**
     * Whether another row follows the current position.
     */
    protected abstract boolean hasNextRow() throws SQLException;

    /**
     * The value of a column (1-based, already checked) in the current row, or {@code null}.
     */
    protected abstract Object currentValue(int columnIndex) throws SQLException;

    /**
     * Releases whatever the subclass holds. Called once, from {@link #close()}.
     */
    protected void release() throws SQLException {
    }

    /**
     * The result's columns, in order.
     */
    protected final List<ResultColumn> columns() {
        return columns;
    }

    // Cursor

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (afterLast) {
            return false;
        }

        if (advance()) {
            row++;
            onRow = true;
            return true;
        }

        onRow = false;
        afterLast = true;
        return false;
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            onRow = false;
            release();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return row == 0 && !afterLast && hasNextRow();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return afterLast && row > 0;
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return onRow && row == 1;
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        return onRow && !hasNextRow();
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return onRow ? row : 0;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != FETCH_FORWARD) {
            throw forwardOnly();
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();

        if (rows < 0) {
            throw new SQLException("Fetch size can't be negative", "HY024");
        }

        fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public Statement getStatement() throws SQLException {
        checkOpen();
        return statement;
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        return new QdrantResultSetMetaData(columns);
    }

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();

        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(columnLabel)) {
                return i + 1;
            }
        }

        throw new SQLException("No column named " + columnLabel, "42S22");
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

    @Override
    public String getCursorName() throws SQLException {
        throw new SQLFeatureNotSupportedException("Named cursors aren't supported");
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        checkOpen();
        return false;
    }

    // Getters by index

    @Override
    public Object getObject(int columnIndex) throws SQLException {
        return value(columnIndex);
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        return ValueConversions.toType(value(columnIndex), type);
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        return value(columnIndex);
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        return ValueConversions.toText(value(columnIndex));
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return ValueConversions.toBoolean(value(columnIndex));
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) ValueConversions.toLong(value(columnIndex), "TINYINT",
            Byte.MIN_VALUE, Byte.MAX_VALUE);
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) ValueConversions.toLong(value(columnIndex), "SMALLINT",
            Short.MIN_VALUE, Short.MAX_VALUE);
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return (int) ValueConversions.toLong(value(columnIndex), "INTEGER",
            Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return ValueConversions.toLong(value(columnIndex), "BIGINT",
            Long.MIN_VALUE, Long.MAX_VALUE);
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return (float) ValueConversions.toDouble(value(columnIndex));
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return ValueConversions.toDouble(value(columnIndex));
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return ValueConversions.toBigDecimal(value(columnIndex));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal value = getBigDecimal(columnIndex);
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        Object value = value(columnIndex);

        if (value == null) {
            return null;
        }

        if (value instanceof byte[]) {
            return (byte[]) value;
        }

        return ValueConversions.toText(value).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        return ValueConversions.toDate(value(columnIndex));
    }

    @Override
    public Date getDate(int columnIndex, Calendar calendar) throws SQLException {
        return getDate(columnIndex);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        return ValueConversions.toTime(value(columnIndex));
    }

    @Override
    public Time getTime(int columnIndex, Calendar calendar) throws SQLException {
        return getTime(columnIndex);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        return ValueConversions.toTimestamp(value(columnIndex));
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar calendar) throws SQLException {
        return getTimestamp(columnIndex);
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null ? null
            : new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null ? null
            : new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16BE));
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] value = getBytes(columnIndex);
        return value == null ? null : new ByteArrayInputStream(value);
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null ? null : new StringReader(value);
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    @Override
    public Ref getRef(int columnIndex) throws SQLException {
        throw unsupportedType("REF");
    }

    @Override
    public Blob getBlob(int columnIndex) throws SQLException {
        throw unsupportedType("BLOB");
    }

    @Override
    public Clob getClob(int columnIndex) throws SQLException {
        throw unsupportedType("CLOB");
    }

    @Override
    public NClob getNClob(int columnIndex) throws SQLException {
        throw unsupportedType("NCLOB");
    }

    @Override
    public Array getArray(int columnIndex) throws SQLException {
        throw unsupportedType("ARRAY");
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        throw unsupportedType("DATALINK");
    }

    @Override
    public RowId getRowId(int columnIndex) throws SQLException {
        throw unsupportedType("ROWID");
    }

    @Override
    public SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw unsupportedType("SQLXML");
    }

    // Getters by label

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(columnLabel), map);
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getNString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Date getDate(String columnLabel, Calendar calendar) throws SQLException {
        return getDate(findColumn(columnLabel), calendar);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Time getTime(String columnLabel, Calendar calendar) throws SQLException {
        return getTime(findColumn(columnLabel), calendar);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar calendar) throws SQLException {
        return getTimestamp(findColumn(columnLabel), calendar);
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return getUnicodeStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getNCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Ref getRef(String columnLabel) throws SQLException {
        return getRef(findColumn(columnLabel));
    }

    @Override
    public Blob getBlob(String columnLabel) throws SQLException {
        return getBlob(findColumn(columnLabel));
    }

    @Override
    public Clob getClob(String columnLabel) throws SQLException {
        return getClob(findColumn(columnLabel));
    }

    @Override
    public NClob getNClob(String columnLabel) throws SQLException {
        return getNClob(findColumn(columnLabel));
    }

    @Override
    public Array getArray(String columnLabel) throws SQLException {
        return getArray(findColumn(columnLabel));
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return getURL(findColumn(columnLabel));
    }

    @Override
    public RowId getRowId(String columnLabel) throws SQLException {
        return getRowId(findColumn(columnLabel));
    }

    @Override
    public SQLXML getSQLXML(String columnLabel) throws SQLException {
        return getSQLXML(findColumn(columnLabel));
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

    // Helpers

    private Object value(int columnIndex) throws SQLException {
        checkOpen();
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException(
                "Column index " + columnIndex + " is out of range 1.." + columns.size(),
                "07009");
        }

        if (!onRow) {
            throw new SQLException("The cursor isn't on a row; call next() first", "24000");
        }

        Object value = currentValue(columnIndex);
        wasNull = value == null;

        return value;
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("The result set is closed", "24000");
        }
    }

    private static SQLFeatureNotSupportedException unsupportedType(String type) {
        return new SQLFeatureNotSupportedException("The driver doesn't return " + type + " values");
    }
}
