package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import io.grpc.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class QdrantConnectionTest {

    private FakeQdrantServer server;
    private Connection connection;

    @BeforeEach
    void connect() throws Exception {
        server = FakeQdrantServer.start();
        connection = new QdrantDriver().connect(server.jdbcUrl(), new Properties());
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
        server.close();
    }

    @Test
    void checksTheServerAndTheApiKeyOnConnect() {
        assertInstanceOf(QdrantConnection.class, connection);
        assertEquals(1, server.calls("HealthCheck"));
        assertEquals(1, server.calls("List"));
    }

    @Test
    void namesTheServerInToString() {
        assertEquals("QdrantConnection[localhost:" + server.port() + ", Qdrant "
            + FakeQdrantServer.VERSION + "]", connection.toString());
    }

    @Test
    void closesOnce() throws SQLException {
        connection.close();
        connection.close();

        assertTrue(connection.isClosed());
    }

    @Test
    void refusesWorkOnceClosed() throws SQLException {
        connection.close();

        List<Executable> calls = List.of(
            connection::createStatement,
            () -> connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY),
            connection::getMetaData,
            () -> connection.setAutoCommit(true),
            connection::getAutoCommit,
            connection::commit,
            connection::rollback,
            () -> connection.nativeSQL("SELECT 1"),
            connection::getWarnings,
            connection::clearWarnings,
            connection::isReadOnly,
            connection::getCatalog,
            connection::getSchema,
            connection::getTypeMap,
            connection::getHoldability,
            connection::getTransactionIsolation,
            () -> connection.getClientInfo("ApplicationName"),
            connection::getClientInfo,
            connection::getNetworkTimeout);

        for (Executable call : calls) {
            SQLException error = assertThrows(SQLException.class, call);
            assertEquals("08003", error.getSQLState());
        }
    }

    @Test
    void checksValidityAgainstTheServer() throws SQLException {
        assertTrue(connection.isValid(5));

        server.fail("List", Status.UNAVAILABLE);
        assertFalse(connection.isValid(5));

        server.recover("List");
        assertTrue(connection.isValid(0));
    }

    @Test
    void isNotValidOnceClosed() throws SQLException {
        connection.close();

        assertFalse(connection.isValid(5));
    }

    @Test
    void rejectsANegativeValidityTimeout() {
        assertThrows(SQLException.class, () -> connection.isValid(-1));
    }

    @Test
    void abortsThroughTheExecutor() throws SQLException {
        List<Runnable> tasks = new ArrayList<>();

        connection.abort(tasks::add);
        connection.abort(tasks::add);

        assertTrue(connection.isClosed());
        assertEquals(1, tasks.size(), "the channel is released once");
        assertDoesNotThrow(() -> tasks.get(0).run());
    }

    @Test
    void needsAnExecutorToAbort() {
        assertThrows(SQLException.class, () -> connection.abort(null));
    }

    @Test
    void downgradesScrollableStatementsWithAWarning() throws SQLException {
        try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY)) {
            assertNull(connection.getWarnings());
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, statement.getResultSetType());
        }

        try (Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
            ResultSet.CONCUR_UPDATABLE, ResultSet.HOLD_CURSORS_OVER_COMMIT)) {
            assertEquals(ResultSet.TYPE_FORWARD_ONLY, statement.getResultSetType());
            assertEquals(ResultSet.CONCUR_READ_ONLY, statement.getResultSetConcurrency());
        }

        assertTrue(connection.getWarnings().getMessage().contains("forward-only"));
    }

    @Test
    void doesNotSupportPreparedStatementsOrCallsYet() {
        List<Executable> calls = List.of(
            () -> connection.prepareStatement("SELECT 1"),
            () -> connection.prepareStatement("SELECT 1", ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY),
            () -> connection.prepareStatement("SELECT 1", ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT),
            () -> connection.prepareStatement("SELECT 1", Statement.RETURN_GENERATED_KEYS),
            () -> connection.prepareStatement("SELECT 1", new int[] {1}),
            () -> connection.prepareStatement("SELECT 1", new String[] {"id"}),
            () -> connection.prepareCall("CALL x()"),
            () -> connection.prepareCall("CALL x()", ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY),
            () -> connection.prepareCall("CALL x()", ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT));

        for (Executable call : calls) {
            assertThrows(SQLFeatureNotSupportedException.class, call);
        }
    }

    @Test
    void treatsTransactionsAsAlwaysAutoCommit() throws SQLException {
        connection.setAutoCommit(true);
        assertNull(connection.getWarnings());

        connection.setAutoCommit(false);
        assertTrue(connection.getAutoCommit());
        assertTrue(connection.getWarnings().getMessage().contains("no transactions"));

        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        assertEquals(Connection.TRANSACTION_NONE, connection.getTransactionIsolation());
        assertDoesNotThrow(connection::commit);
        assertDoesNotThrow(() -> connection.rollback());
    }

    @Test
    void doesNotSupportSavepoints() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> connection.setSavepoint());
        assertThrows(SQLFeatureNotSupportedException.class, () -> connection.setSavepoint("s"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> connection.rollback(null));
        assertThrows(SQLFeatureNotSupportedException.class, () -> connection.releaseSavepoint(null));
    }

    @Test
    void ignoresCatalogSchemaAndReadOnlySettings() throws SQLException {
        connection.setReadOnly(false);
        connection.setCatalog("anything");
        connection.setSchema("anything");

        assertTrue(connection.isReadOnly());
        assertNull(connection.getCatalog());
        assertNull(connection.getSchema());
    }

    @Test
    void chainsAndClearsWarnings() throws SQLException {
        connection.setAutoCommit(false);
        connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY)
            .close();

        SQLWarning warnings = connection.getWarnings();
        assertTrue(warnings.getMessage().contains("transactions"));
        assertTrue(warnings.getNextWarning().getMessage().contains("forward-only"));

        connection.clearWarnings();
        assertNull(connection.getWarnings());
    }

    @Test
    void keepsClientInfo() throws SQLException {
        connection.setClientInfo("ApplicationName", "DataGrip");
        assertEquals("DataGrip", connection.getClientInfo("ApplicationName"));

        connection.setClientInfo("ApplicationName", null);
        assertNull(connection.getClientInfo("ApplicationName"));

        Properties properties = new Properties();
        properties.setProperty("ClientUser", "maksym");
        connection.setClientInfo(properties);
        Properties copy = connection.getClientInfo();
        assertEquals(Map.of("ClientUser", "maksym"), copy);

        copy.setProperty("ClientUser", "changed");
        assertEquals("maksym", connection.getClientInfo("ClientUser"));
    }

    @Test
    void refusesClientInfoOnceClosed() throws SQLException {
        connection.close();

        assertThrows(SQLClientInfoException.class,
            () -> connection.setClientInfo("ApplicationName", "DataGrip"));
        assertThrows(SQLClientInfoException.class,
            () -> connection.setClientInfo(new Properties()));
    }

    @Test
    void keepsTheNetworkTimeoutHoldabilityAndTypeMap() throws SQLException {
        connection.setNetworkTimeout(Runnable::run, 5000);
        connection.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        connection.setTypeMap(Map.of());

        assertEquals(5000, connection.getNetworkTimeout());
        assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, connection.getHoldability());
        assertTrue(connection.getTypeMap().isEmpty());
        assertThrows(SQLException.class, () -> connection.setNetworkTimeout(Runnable::run, -1));
    }

    @Test
    void doesNotCreateLargeObjectsOrStructuredValues() {
        assertThrows(SQLFeatureNotSupportedException.class, connection::createClob);
        assertThrows(SQLFeatureNotSupportedException.class, connection::createBlob);
        assertThrows(SQLFeatureNotSupportedException.class, connection::createNClob);
        assertThrows(SQLFeatureNotSupportedException.class, connection::createSQLXML);
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> connection.createArrayOf("VARCHAR", new Object[0]));
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> connection.createStruct("T", new Object[0]));
    }

    @Test
    void passesSqlThroughUnchanged() throws SQLException {
        assertEquals("SELECT {fn now()}", connection.nativeSQL("SELECT {fn now()}"));
    }

    @Test
    void handsOutMetadataForItself() throws SQLException {
        assertSame(connection, connection.getMetaData().getConnection());
    }

    @Test
    void unwrapsToItself() throws SQLException {
        assertTrue(connection.isWrapperFor(QdrantConnection.class));
        assertFalse(connection.isWrapperFor(String.class));
        assertSame(connection, connection.unwrap(Connection.class));
        assertThrows(SQLException.class, () -> connection.unwrap(String.class));
    }
}
