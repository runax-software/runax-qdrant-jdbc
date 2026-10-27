package com.runaxsoftware.qdrant.jdbc.resultset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

class ListResultSetTest {

    private static final List<ResultColumn> COLUMNS = List.of(
            ResultColumn.text("NAME"), ResultColumn.smallint("SEQ"), ResultColumn.bigint("COUNT"),
            ResultColumn.bool("FLAG"));

    private static ListResultSet twoRows() {
        return new ListResultSet(COLUMNS, List.of(
                new Object[] {"first", (short) 1, 10L, true},
                new Object[] {null, (short) 2, Long.MAX_VALUE, null}), null);
    }

    @Test
    void readsRowsInOrder() throws SQLException {
        ListResultSet rows = twoRows();

        assertTrue(rows.next());
        assertEquals("first", rows.getString(1));
        assertEquals(1, rows.getInt("seq"));
        assertEquals(10L, rows.getLong("COUNT"));
        assertTrue(rows.getBoolean(4));
        assertTrue(rows.next());
        assertFalse(rows.next());
        assertFalse(rows.next());
    }

    @Test
    void reportsNulls() throws SQLException {
        ListResultSet rows = twoRows();
        rows.next();
        rows.next();

        assertNull(rows.getString(1));
        assertTrue(rows.wasNull());
        assertFalse(rows.getBoolean(4));
        assertTrue(rows.wasNull());
        assertEquals(2, rows.getShort(2));
        assertFalse(rows.wasNull());
    }

    @Test
    void convertsBetweenTypes() throws SQLException {
        ListResultSet rows = twoRows();
        rows.next();

        assertEquals("1", rows.getString(2));
        assertEquals(new BigDecimal("10"), rows.getBigDecimal(3));
        assertEquals(10.0, rows.getDouble(3));
        assertEquals(Long.valueOf(10), rows.getObject(3, Long.class));
        assertEquals("true", rows.getObject(4, String.class));
    }

    @Test
    void rejectsValuesOutOfRange() throws SQLException {
        ListResultSet rows = twoRows();
        rows.next();
        rows.next();

        SQLDataException error = assertThrows(SQLDataException.class, () -> rows.getInt(3));
        assertEquals("22003", error.getSQLState());
    }

    @Test
    void readsDateTimesFromText() throws SQLException {
        ListResultSet rows = new ListResultSet(List.of(ResultColumn.text("AT")),
                List.<Object[]>of(new Object[] {"2024-03-01T10:00:00Z"}), null);
        rows.next();

        assertEquals(OffsetDateTime.of(2024, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                rows.getObject(1, OffsetDateTime.class));
        assertEquals(Timestamp.from(Instant.parse("2024-03-01T10:00:00Z")), rows.getTimestamp(1));
    }

    @Test
    void tracksTheCursorPosition() throws SQLException {
        ListResultSet rows = twoRows();

        assertTrue(rows.isBeforeFirst());
        assertEquals(0, rows.getRow());
        rows.next();
        assertTrue(rows.isFirst());
        assertFalse(rows.isLast());
        rows.next();
        assertTrue(rows.isLast());
        assertEquals(2, rows.getRow());
        rows.next();
        assertTrue(rows.isAfterLast());
        assertEquals(0, rows.getRow());
    }

    @Test
    void anEmptyResultIsNeitherBeforeFirstNorAfterLast() throws SQLException {
        ListResultSet rows = ListResultSet.empty(COLUMNS);

        assertFalse(rows.isBeforeFirst());
        assertFalse(rows.next());
        assertFalse(rows.isAfterLast());
    }

    @Test
    void refusesReadsOffARow() throws SQLException {
        ListResultSet rows = twoRows();

        assertEquals("24000", assertThrows(SQLException.class, () -> rows.getString(1)).getSQLState());
        rows.next();
        assertEquals("07009", assertThrows(SQLException.class, () -> rows.getString(5)).getSQLState());
        assertEquals("42S22",
                assertThrows(SQLException.class, () -> rows.getString("missing")).getSQLState());
    }

    @Test
    void isForwardOnlyAndReadOnly() throws SQLException {
        ListResultSet rows = twoRows();
        rows.next();

        assertThrows(SQLFeatureNotSupportedException.class, rows::previous);
        assertThrows(SQLFeatureNotSupportedException.class, () -> rows.absolute(1));
        assertThrows(SQLFeatureNotSupportedException.class, () -> rows.updateString(1, "x"));
        assertThrows(SQLFeatureNotSupportedException.class, rows::updateRow);
    }

    @Test
    void refusesUseAfterClose() throws SQLException {
        ListResultSet rows = twoRows();
        rows.close();

        assertTrue(rows.isClosed());
        assertThrows(SQLException.class, rows::next);
    }

    @Test
    void describesItsColumns() throws SQLException {
        ResultSetMetaData metaData = twoRows().getMetaData();

        assertEquals(4, metaData.getColumnCount());
        assertEquals("SEQ", metaData.getColumnLabel(2));
        assertEquals(Types.SMALLINT, metaData.getColumnType(2));
        assertEquals("SMALLINT", metaData.getColumnTypeName(2));
        assertEquals(Short.class.getName(), metaData.getColumnClassName(2));
        assertTrue(metaData.isReadOnly(1));
    }

    @Test
    void rejectsRowsOfTheWrongLength() {
        assertThrows(IllegalArgumentException.class,
            () -> new ListResultSet(COLUMNS, List.<Object[]>of(new Object[] {"only one"}), null));
    }

    @Test
    void keepsItsOwnCopyOfTheRows() throws SQLException {
        java.util.List<Object[]> source = new java.util.ArrayList<>();
        source.add(new Object[] {"a", (short) 1, 1L, true});
        ListResultSet rows = new ListResultSet(COLUMNS, source, null);

        source.add(new Object[] {"b", (short) 2, 2L, false});

        assertTrue(rows.next());
        assertFalse(rows.next());
    }

    @Test
    void readsColumnsCaseInsensitively() throws SQLException {
        ListResultSet rows = twoRows();
        rows.next();

        assertEquals(1, rows.findColumn("name"));
        assertEquals(4, rows.findColumn("Flag"));
    }
}
