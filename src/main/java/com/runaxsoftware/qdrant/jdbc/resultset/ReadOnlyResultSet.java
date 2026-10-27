package com.runaxsoftware.qdrant.jdbc.resultset;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * The parts of {@link ResultSet} the driver's result sets refuse: they're forward-only, so they
 * can't scroll back or jump to a row, and read-only, so every update method throws.
 *
 * <p>{@link AbstractResultSet} adds the cursor and the getters.
 */
abstract class ReadOnlyResultSet implements ResultSet {

    // Scrolling: result sets are forward-only

    @Override
    public void beforeFirst() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public void afterLast() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean first() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean last() throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw forwardOnly();
    }

    @Override
    public boolean previous() throws SQLException {
        throw forwardOnly();
    }

    // Updates: result sets are read-only

    @Override
    public void insertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void deleteRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void refreshRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(int columnIndex, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(int columnIndex, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(int columnIndex, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, NClob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(int columnIndex, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(int columnIndex, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(int columnIndex, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(int columnIndex, SQLXML x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNString(String columnLabel, String x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x, int length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x, long length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader x, long length)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength)
        throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRef(String columnLabel, Ref x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateBlob(String columnLabel, InputStream x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Clob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateClob(String columnLabel, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, NClob x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader x, long length) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateNClob(String columnLabel, Reader x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateArray(String columnLabel, Array x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateRowId(String columnLabel, RowId x) throws SQLException {
        throw readOnly();
    }

    @Override
    public void updateSQLXML(String columnLabel, SQLXML x) throws SQLException {
        throw readOnly();
    }

    static SQLFeatureNotSupportedException forwardOnly() {
        return new SQLFeatureNotSupportedException("Result sets are forward-only");
    }

    private static SQLFeatureNotSupportedException readOnly() {
        return new SQLFeatureNotSupportedException("Result sets are read-only");
    }
}
