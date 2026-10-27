package com.runaxsoftware.qdrant.jdbc.statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.QdrantDriver;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Properties;

class QdrantStatementTest {

    private static final String SQL = "SELECT * FROM products";

    private FakeQdrantServer server;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void open() throws Exception {
        server = FakeQdrantServer.start();
        connection = new QdrantDriver().connect(server.jdbcUrl(), new Properties());
        statement = connection.createStatement();
    }

    @AfterEach
    void close() throws Exception {
        statement.close();
        connection.close();
        server.close();
    }

    @Test
    void doesNotRunSqlYet() {
        List<Executable> calls = List.of(
            () -> statement.executeQuery(SQL),
            () -> statement.execute(SQL),
            () -> statement.execute(SQL, Statement.RETURN_GENERATED_KEYS),
            () -> statement.execute(SQL, new int[] {1}),
            () -> statement.execute(SQL, new String[] {"id"}),
            () -> statement.executeUpdate(SQL),
            () -> statement.executeUpdate(SQL, Statement.NO_GENERATED_KEYS),
            () -> statement.executeUpdate(SQL, new int[] {1}),
            () -> statement.executeUpdate(SQL, new String[] {"id"}),
            () -> statement.executeLargeUpdate(SQL),
            () -> statement.executeLargeUpdate(SQL, Statement.NO_GENERATED_KEYS),
            () -> statement.executeLargeUpdate(SQL, new int[] {1}),
            () -> statement.executeLargeUpdate(SQL, new String[] {"id"}));

        for (Executable call : calls) {
            SQLFeatureNotSupportedException error =
                assertThrows(SQLFeatureNotSupportedException.class, call);
            assertTrue(error.getMessage().contains("isn't supported yet"));
        }
    }

    @Test
    void reportsSyntaxErrorsBeforeSayingSqlCantRunYet() {
        String broken = "SELECT * FROM products WHERE price >";
        List<Executable> calls = List.of(
            () -> statement.executeQuery(broken),
            () -> statement.execute(broken),
            () -> statement.execute(broken, Statement.RETURN_GENERATED_KEYS),
            () -> statement.executeUpdate(broken),
            () -> statement.executeLargeUpdate(broken, new String[] {"id"}));

        for (Executable call : calls) {
            SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class, call);
            assertEquals("Expected a value, found the end of the statement at line 1, column 37",
                error.getMessage());
        }
    }

    @Test
    void returnsTheRowOfASelectWithoutFrom() throws SQLException {
        try (ResultSet rows = statement.executeQuery("select 'keep alive'")) {
            assertEquals(1, rows.getMetaData().getColumnCount());
            assertEquals("?column?", rows.getMetaData().getColumnLabel(1));
            assertTrue(rows.next());
            assertEquals("keep alive", rows.getString(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void namesAndTypesTheColumnsOfConstants() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT 1 AS one, 2.5 AS half, true AS yes, 'x'")) {
            ResultSetMetaData metadata = rows.getMetaData();

            assertEquals(List.of("one", "half", "yes", "?column?"), List.of(metadata.getColumnLabel(1),
                metadata.getColumnLabel(2), metadata.getColumnLabel(3), metadata.getColumnLabel(4)));
            assertEquals(List.of(Types.BIGINT, Types.DOUBLE, Types.BOOLEAN, Types.VARCHAR), List.of(
                metadata.getColumnType(1), metadata.getColumnType(2), metadata.getColumnType(3),
                metadata.getColumnType(4)));

            assertTrue(rows.next());
            assertEquals(1, rows.getLong("one"));
            assertEquals(2.5, rows.getDouble("half"));
            assertTrue(rows.getBoolean("yes"));
            assertEquals("x", rows.getString(4));
        }
    }

    @Test
    void returnsNoRowsWhenLimitOrOffsetSkipsTheRow() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT 1 LIMIT 0")) {
            assertFalse(rows.next());
        }
        try (ResultSet rows = statement.executeQuery("SELECT 1 OFFSET 1")) {
            assertFalse(rows.next());
        }
    }

    @Test
    void keepsTheResultOfExecuteUntilTheNextResultIsAskedFor() throws SQLException {
        assertTrue(statement.execute("SELECT 1"));

        ResultSet rows = statement.getResultSet();
        assertNotNull(rows);
        assertSame(statement, rows.getStatement());
        assertEquals(-1, statement.getUpdateCount());

        assertFalse(statement.getMoreResults());
        assertTrue(rows.isClosed());
        assertNull(statement.getResultSet());
    }

    @Test
    void closesThePreviousResultOnTheNextRun() throws SQLException {
        ResultSet first = statement.executeQuery("SELECT 1");
        ResultSet second = statement.executeQuery("SELECT 2");

        assertTrue(first.isClosed());
        assertFalse(second.isClosed());
        assertSame(second, statement.getResultSet());
    }

    @Test
    void closesItsResultWhenClosed() throws SQLException {
        ResultSet rows = statement.executeQuery("SELECT 1");
        statement.close();

        assertTrue(rows.isClosed());
    }

    @Test
    void refusesASelectInExecuteUpdate() {
        SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate("SELECT 1"));

        assertFalse(error instanceof SQLFeatureNotSupportedException);
        assertTrue(error.getMessage().contains("executeUpdate can't run a SELECT"), error.getMessage());
    }

    @Test
    void reportsUnknownColumnsInASelectWithoutFrom() {
        SQLSyntaxErrorException error =
            assertThrows(SQLSyntaxErrorException.class, () -> statement.executeQuery("SELECT name"));

        assertEquals("42S22", error.getSQLState());
    }

    @Test
    void reportsEmptySqlAsASyntaxError() {
        assertThrows(SQLSyntaxErrorException.class, () -> statement.executeQuery(""));
        assertThrows(SQLSyntaxErrorException.class, () -> statement.executeQuery("   "));
    }

    @Test
    void rejectsNullSql() {
        List<Executable> calls = List.of(
            () -> statement.executeQuery(null),
            () -> statement.execute(null),
            () -> statement.executeUpdate(null));

        for (Executable call : calls) {
            assertEquals("HY009", assertThrows(SQLException.class, call).getSQLState());
        }
    }

    @Test
    void doesNotSupportBatchesGeneratedKeysOrCursorNames() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.addBatch(SQL));
        assertThrows(SQLFeatureNotSupportedException.class, statement::clearBatch);
        assertThrows(SQLFeatureNotSupportedException.class, statement::executeBatch);
        assertThrows(SQLFeatureNotSupportedException.class, statement::executeLargeBatch);
        assertThrows(SQLFeatureNotSupportedException.class, statement::getGeneratedKeys);
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.setCursorName("c"));
    }

    @Test
    void hasNoResultsBeforeRunningAnything() throws SQLException {
        assertNull(statement.getResultSet());
        assertEquals(-1, statement.getUpdateCount());
        assertEquals(-1, statement.getLargeUpdateCount());
        assertFalse(statement.getMoreResults());
        assertFalse(statement.getMoreResults(Statement.CLOSE_ALL_RESULTS));
        assertNull(statement.getWarnings());
        assertDoesNotThrow(statement::clearWarnings);
        assertDoesNotThrow(statement::cancel);
    }

    @Test
    void keepsTheSettingsToolsApply() throws SQLException {
        statement.setMaxRows(500);
        statement.setMaxFieldSize(1024);
        statement.setFetchSize(100);
        statement.setQueryTimeout(30);
        statement.setPoolable(true);
        statement.setEscapeProcessing(false);
        statement.closeOnCompletion();

        assertEquals(500, statement.getMaxRows());
        assertEquals(500, statement.getLargeMaxRows());
        assertEquals(1024, statement.getMaxFieldSize());
        assertEquals(100, statement.getFetchSize());
        assertEquals(30, statement.getQueryTimeout());
        assertTrue(statement.isPoolable());
        assertTrue(statement.isCloseOnCompletion());
    }

    @Test
    void capsLargeMaxRowsForTheIntGetter() throws SQLException {
        statement.setLargeMaxRows(5_000_000_000L);

        assertEquals(5_000_000_000L, statement.getLargeMaxRows());
        assertEquals(Integer.MAX_VALUE, statement.getMaxRows());
    }

    @Test
    void acceptsEveryFetchDirectionAsAHint() throws SQLException {
        for (int direction : new int[] {ResultSet.FETCH_REVERSE, ResultSet.FETCH_UNKNOWN,
            ResultSet.FETCH_FORWARD}) {
            statement.setFetchDirection(direction);
            assertEquals(direction, statement.getFetchDirection());
        }

        assertEquals("HY024",
            assertThrows(SQLException.class, () -> statement.setFetchDirection(42)).getSQLState());
    }

    @Test
    void rejectsNegativeSettings() {
        List<Executable> calls = List.of(
            () -> statement.setMaxRows(-1),
            () -> statement.setLargeMaxRows(-1),
            () -> statement.setMaxFieldSize(-1),
            () -> statement.setFetchSize(-1),
            () -> statement.setQueryTimeout(-1));

        for (Executable call : calls) {
            assertEquals("HY024", assertThrows(SQLException.class, call).getSQLState());
        }
    }

    @Test
    void describesItsResultSets() throws SQLException {
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, statement.getResultSetType());
        assertEquals(ResultSet.CONCUR_READ_ONLY, statement.getResultSetConcurrency());
        assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, statement.getResultSetHoldability());
    }

    @Test
    void belongsToItsConnection() throws SQLException {
        assertSame(connection, statement.getConnection());
    }

    @Test
    void refusesWorkOnceClosed() throws SQLException {
        statement.close();
        statement.close();

        assertTrue(statement.isClosed());
        assertEquals("HY010", assertThrows(SQLException.class, statement::getMaxRows).getSQLState());
        assertEquals("HY010",
            assertThrows(SQLException.class, () -> statement.executeQuery(SQL)).getSQLState());
    }

    @Test
    void refusesWorkOnceTheConnectionIsClosed() throws SQLException {
        connection.close();

        assertEquals("08003", assertThrows(SQLException.class, statement::getMaxRows).getSQLState());
    }

    @Test
    void unwrapsToItself() throws SQLException {
        assertTrue(statement.isWrapperFor(QdrantStatement.class));
        assertFalse(statement.isWrapperFor(Connection.class));
        assertSame(statement, statement.unwrap(Statement.class));
        assertThrows(SQLException.class, () -> statement.unwrap(Connection.class));
    }
}
