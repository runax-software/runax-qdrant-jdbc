package com.runaxsoftware.qdrant.jdbc.resultset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The behaviour every driver result set shares, through the in-memory {@link ListResultSet}. */
class AbstractResultSetTest {

    private static final List<ResultColumn> COLUMNS = List.of(
        ResultColumn.text("TEXT"), ResultColumn.bigint("NUMBER"), ResultColumn.bool("FLAG"),
        ResultColumn.text("AT"), ResultColumn.of("DECIMAL", java.sql.Types.DECIMAL),
        ResultColumn.of("BYTES", java.sql.Types.VARBINARY), ResultColumn.text("NOTHING"));

    private ListResultSet rows;

    @BeforeEach
    void positionOnTheRow() throws SQLException {
        rows = new ListResultSet(COLUMNS, List.<Object[]>of(new Object[] {
            "héllo", 300L, true, "2024-03-01T10:15:30Z", new BigDecimal("2.345"),
            new byte[] {1, 2, 3}, null}), null);
        rows.next();
    }

    @Test
    void readsTheSameValuesByLabelAndByIndex() throws SQLException {
        assertEquals(rows.getString(1), rows.getString("text"));
        assertEquals(rows.getNString(1), rows.getNString("TEXT"));
        assertEquals(rows.getLong(2), rows.getLong("NUMBER"));
        assertEquals(rows.getInt(2), rows.getInt("NUMBER"));
        assertEquals(rows.getShort(2), rows.getShort("NUMBER"));
        assertEquals(rows.getFloat(2), rows.getFloat("NUMBER"));
        assertEquals(rows.getDouble(2), rows.getDouble("NUMBER"));
        assertEquals(rows.getBigDecimal(5), rows.getBigDecimal("DECIMAL"));
        assertEquals(rows.getBoolean(3), rows.getBoolean("FLAG"));
        assertEquals(rows.getObject(2), rows.getObject("NUMBER"));
        assertEquals(rows.getObject(2, Long.class), rows.getObject("NUMBER", Long.class));
        assertEquals(rows.getObject(2, Map.of()), rows.getObject("NUMBER", Map.of()));
        assertEquals(rows.getTimestamp(4), rows.getTimestamp("AT"));
        assertEquals(rows.getDate(4), rows.getDate("AT"));
        assertEquals(rows.getTime(4), rows.getTime("AT"));
        assertArrayEquals(rows.getBytes(6), rows.getBytes("BYTES"));
    }

    @Test
    void ignoresTheCalendarForDateTimes() throws SQLException {
        Calendar calendar = Calendar.getInstance();

        assertEquals(rows.getTimestamp(4), rows.getTimestamp(4, calendar));
        assertEquals(rows.getTimestamp(4), rows.getTimestamp("AT", calendar));
        assertEquals(rows.getDate(4), rows.getDate(4, calendar));
        assertEquals(rows.getDate(4), rows.getDate("AT", calendar));
        assertEquals(rows.getTime(4), rows.getTime(4, calendar));
        assertEquals(rows.getTime(4), rows.getTime("AT", calendar));
    }

    @Test
    void readsBytesAsUtf8TextOrAsTheyAre() throws SQLException {
        assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), rows.getBytes(1));
        assertArrayEquals(new byte[] {1, 2, 3}, rows.getBytes(6));
        assertNull(rows.getBytes(7));
    }

    @Test
    void readsStreams() throws Exception {
        try (InputStream ascii = rows.getAsciiStream("TEXT");
             InputStream binary = rows.getBinaryStream("BYTES");
             Reader characters = rows.getCharacterStream("TEXT");
             Reader nationalCharacters = rows.getNCharacterStream(1)) {
            assertEquals("h?llo", new String(ascii.readAllBytes(), StandardCharsets.US_ASCII));
            assertArrayEquals(new byte[] {1, 2, 3}, binary.readAllBytes());
            assertEquals("héllo", read(characters));
            assertEquals("héllo", read(nationalCharacters));
        }

        assertNull(rows.getAsciiStream(7));
        assertNull(rows.getBinaryStream(7));
        assertNull(rows.getCharacterStream(7));
    }

    @Test
    @SuppressWarnings("deprecation")
    void supportsTheDeprecatedGetters() throws Exception {
        assertEquals(new BigDecimal("2.35"), rows.getBigDecimal(5, 2));
        assertEquals(new BigDecimal("2.3"), rows.getBigDecimal("DECIMAL", 1));
        assertNull(rows.getBigDecimal(7, 2));

        try (InputStream unicode = rows.getUnicodeStream("TEXT")) {
            assertEquals("héllo", new String(unicode.readAllBytes(), StandardCharsets.UTF_16BE));
        }

        assertNull(rows.getUnicodeStream(7));
    }

    @Test
    void rejectsSmallIntegersOutOfRange() {
        assertEquals("22003", assertThrows(SQLDataException.class, () -> rows.getByte(2))
            .getSQLState());
        assertEquals("22003", assertThrows(SQLDataException.class, () -> rows.getByte("NUMBER"))
            .getSQLState());
    }

    @Test
    void returnsZeroAndFalseForNulls() throws SQLException {
        assertEquals(0, rows.getInt(7));
        assertTrue(rows.wasNull());
        assertEquals(0, rows.getDouble("NOTHING"));
        assertFalse(rows.getBoolean(7));
        assertNull(rows.getString(7));
        assertNull(rows.getObject(7, Long.class));
        assertNull(rows.getTimestamp(7));
        assertEquals("héllo", rows.getString(1));
        assertFalse(rows.wasNull());
    }

    @Test
    void doesNotReturnLargeObjectsOrStructuredValues() throws Exception {
        for (String name : List.of("getRef", "getBlob", "getClob", "getNClob", "getArray", "getURL",
            "getRowId", "getSQLXML")) {
            assertRejected(ResultSet.class.getMethod(name, int.class), 1);
            assertRejected(ResultSet.class.getMethod(name, String.class), "TEXT");
        }
    }

    @Test
    void rejectsEveryUpdate() throws Exception {
        int checked = 0;

        for (Method method : ResultSet.class.getMethods()) {
            boolean update = method.getName().startsWith("update")
                && !method.getName().equals("updatesAreDetected");
            boolean rowChange = Set.of("insertRow", "updateRow", "deleteRow", "refreshRow",
                "cancelRowUpdates", "moveToInsertRow", "moveToCurrentRow").contains(method.getName());

            if (update || rowChange) {
                assertRejected(method, defaultArguments(method));
                checked++;
            }
        }

        assertTrue(checked > 80, "checked " + checked + " update methods");
    }

    @Test
    void rejectsEveryMoveExceptNext() throws Exception {
        List<Executable> moves = List.of(rows::beforeFirst, rows::afterLast, rows::first,
            rows::last, () -> rows.absolute(1), () -> rows.relative(1), rows::previous,
            () -> rows.setFetchDirection(ResultSet.FETCH_REVERSE));

        for (Executable move : moves) {
            assertThrows(SQLFeatureNotSupportedException.class, move);
        }

        rows.setFetchDirection(ResultSet.FETCH_FORWARD);
        assertEquals(ResultSet.FETCH_FORWARD, rows.getFetchDirection());
    }

    @Test
    void keepsTheFetchSize() throws SQLException {
        rows.setFetchSize(50);

        assertEquals(50, rows.getFetchSize());
        assertEquals("HY024", assertThrows(SQLException.class, () -> rows.setFetchSize(-1))
            .getSQLState());
    }

    @Test
    void describesItself() throws SQLException {
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, rows.getType());
        assertEquals(ResultSet.CONCUR_READ_ONLY, rows.getConcurrency());
        assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, rows.getHoldability());
        assertNull(rows.getStatement());
        assertNull(rows.getWarnings());
        rows.clearWarnings();
        assertFalse(rows.rowUpdated());
        assertFalse(rows.rowInserted());
        assertFalse(rows.rowDeleted());
        assertThrows(SQLFeatureNotSupportedException.class, rows::getCursorName);
    }

    @Test
    void remembersItsStatement() throws SQLException {
        Statement statement = (Statement) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {Statement.class}, (proxy, method, args) -> null);

        assertSame(statement, new ListResultSet(COLUMNS, List.of(), statement).getStatement());
    }

    @Test
    void releasesOnceWhenClosed() throws SQLException {
        Counting counting = new Counting();

        counting.close();
        counting.close();

        assertEquals(1, counting.released);
        assertTrue(counting.isClosed());
    }

    @Test
    void refusesWorkOnceClosed() throws SQLException {
        rows.close();

        List<Executable> calls = List.of(rows::next, () -> rows.getString(1), rows::wasNull,
            rows::getMetaData, () -> rows.findColumn("TEXT"), rows::getRow, rows::isBeforeFirst,
            rows::isAfterLast, rows::isFirst, rows::isLast, rows::getFetchSize, rows::getType,
            rows::getConcurrency, rows::getHoldability, rows::getStatement, rows::getWarnings,
            rows::rowUpdated);

        for (Executable call : calls) {
            assertEquals("24000", assertThrows(SQLException.class, call).getSQLState());
        }
    }

    @Test
    void unwrapsToItself() throws SQLException {
        assertTrue(rows.isWrapperFor(ResultSet.class));
        assertFalse(rows.isWrapperFor(Statement.class));
        assertSame(rows, rows.unwrap(AbstractResultSet.class));
        assertThrows(SQLException.class, () -> rows.unwrap(Statement.class));
    }

    private void assertRejected(Method method, Object... arguments) {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
            () -> method.invoke(rows, arguments), method.toString());

        assertInstanceOf(SQLFeatureNotSupportedException.class, failure.getCause(), method.toString());
    }

    private static Object[] defaultArguments(Method method) {
        Class<?>[] types = method.getParameterTypes();
        Object[] arguments = new Object[types.length];

        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) {
                arguments[i] = 1;
            } else if (types[i] == long.class) {
                arguments[i] = 1L;
            } else if (types[i] == boolean.class) {
                arguments[i] = false;
            } else if (types[i] == byte.class) {
                arguments[i] = (byte) 0;
            } else if (types[i] == short.class) {
                arguments[i] = (short) 0;
            } else if (types[i] == float.class) {
                arguments[i] = 0f;
            } else if (types[i] == double.class) {
                arguments[i] = 0d;
            } else if (types[i] == String.class) {
                arguments[i] = "TEXT";
            }
        }

        return arguments;
    }

    private static String read(Reader reader) throws Exception {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[64];
        int count;

        while ((count = reader.read(buffer)) != -1) {
            text.append(buffer, 0, count);
        }

        return text.toString();
    }

    /** Counts calls to {@link #release()}. */
    private static final class Counting extends AbstractResultSet {
        private int released;

        Counting() {
            super(List.of(ResultColumn.text("A")), null);
        }

        @Override
        protected boolean advance() {
            return false;
        }

        @Override
        protected boolean hasNextRow() {
            return false;
        }

        @Override
        protected Object currentValue(int columnIndex) {
            return null;
        }

        @Override
        protected void release() {
            released++;
        }
    }
}
