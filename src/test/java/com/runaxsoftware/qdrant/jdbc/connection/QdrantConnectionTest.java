package com.runaxsoftware.qdrant.jdbc.connection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.DriverInfo;
import com.runaxsoftware.qdrant.jdbc.QdrantDriver;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.Endpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
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
        if (connection != null) {
            connection.close();
        }

        server.close();
    }

    // Opening

    @Test
    void checksTheServerVersionAndThenTheApiKeyOnConnect() {
        assertInstanceOf(QdrantConnection.class, connection);
        assertEquals(1, server.calls(Endpoint.VERSION));
        assertEquals(1, server.calls(Endpoint.COLLECTIONS));

        List<FakeQdrantServer.Request> requests = server.requests();
        assertEquals(2, requests.size(), requests.toString());
        assertEquals("GET", requests.get(0).method());
        assertEquals("/", requests.get(0).path());
        assertEquals("GET", requests.get(1).method());
        assertEquals("/collections", requests.get(1).path());
    }

    @Test
    void keepsTheServerVersionTheUrlAndASchemaReader() throws SQLException {
        QdrantConnection qdrant = connection.unwrap(QdrantConnection.class);

        assertEquals(FakeQdrantServer.VERSION, qdrant.serverVersion().version());
        assertEquals(1, qdrant.serverVersion().major());
        assertEquals(20, qdrant.serverVersion().minor());
        assertEquals(FakeQdrantServer.COMMIT, qdrant.serverVersion().commit());
        assertEquals(server.jdbcUrl(), qdrant.url());
        assertEquals("http://localhost:" + server.port(), qdrant.gateway().baseUrl());
        assertTrue(qdrant.schemaReader() != null);
    }

    @Test
    void sendsTheDriverAsTheUserAgent() {
        assertFalse(server.userAgents().isEmpty());
        assertTrue(server.userAgents().stream().allMatch(DriverInfo.USER_AGENT::equals),
            server.userAgents().toString());
    }

    @Test
    void sendsNoApiKeyWhenThereIsNone() {
        assertTrue(server.apiKeys().stream().allMatch(String::isEmpty), server.apiKeys().toString());
    }

    @Test
    void sendsThePasswordAsTheApiKey() throws Exception {
        server.requireApiKey("secret");
        Properties properties = new Properties();
        properties.setProperty("password", "secret");

        try (Connection secured = new QdrantDriver().connect(server.jdbcUrl(), properties)) {
            assertTrue(secured.isValid(5));
        }

        List<String> keys = server.apiKeys();
        assertEquals(List.of("secret", "secret", "secret"), keys.subList(keys.size() - 3, keys.size()));
    }

    @Test
    void reportsAWrongApiKeyOnConnect() {
        server.requireApiKey("secret");
        Properties properties = new Properties();
        properties.setProperty("password", "wrong");

        SQLInvalidAuthorizationSpecException error = assertThrows(
            SQLInvalidAuthorizationSpecException.class,
            () -> new QdrantDriver().connect(server.jdbcUrl(), properties));

        assertEquals("28000", error.getSQLState());
        assertTrue(error.getMessage().contains("password field"), error.getMessage());
        assertTrue(error.getMessage().contains("Invalid API key or JWT"), error.getMessage());
        assertFalse(error.getMessage().contains("wrong"), error.getMessage());
    }

    @Test
    void reportsAMissingApiKeyOnConnect() {
        server.requireApiKey("secret");

        SQLInvalidAuthorizationSpecException error = assertThrows(
            SQLInvalidAuthorizationSpecException.class,
            () -> new QdrantDriver().connect(server.jdbcUrl(), new Properties()));

        assertEquals("28000", error.getSQLState());
        assertTrue(error.getMessage().contains("Must provide an API key"), error.getMessage());
    }

    @Test
    void reportsAConnectionErrorWhenNothingListens() throws IOException {
        int port = freePort();
        Properties properties = new Properties();
        properties.setProperty("timeout", "5");

        SQLNonTransientConnectionException error = assertThrows(
            SQLNonTransientConnectionException.class,
            () -> new QdrantDriver().connect("jdbc:qdrant://localhost:" + port, properties));

        assertEquals("08001", error.getSQLState());
        assertTrue(error.getMessage().contains("localhost:" + port), error.getMessage());
        assertTrue(error.getMessage().contains("REST port (6333 by default)"), error.getMessage());
    }

    @Test
    void pointsToTheRestPortWhenSomethingElseAnswers() {
        server.failWithBody(Endpoint.VERSION, 404, "text/plain", "");

        SQLNonTransientConnectionException error = assertThrows(
            SQLNonTransientConnectionException.class,
            () -> new QdrantDriver().connect(server.jdbcUrl(), new Properties()));

        assertEquals("08001", error.getSQLState());
        assertTrue(error.getMessage().contains("Qdrant's REST API didn't answer at http://localhost:"
            + server.port()), error.getMessage());
        assertTrue(error.getMessage().contains("REST port, 6333 by default, not the gRPC port, 6334"),
            error.getMessage());
        assertEquals("42S02", ((SQLException) error.getCause()).getSQLState());
    }

    @Test
    void pointsToTheRestPortWhenTheAnswerIsNotJson() {
        server.failWithBody(Endpoint.VERSION, 200, "text/html", "<html>Not Qdrant</html>");

        SQLNonTransientConnectionException error = assertThrows(
            SQLNonTransientConnectionException.class,
            () -> new QdrantDriver().connect(server.jdbcUrl(), new Properties()));

        assertTrue(error.getMessage().contains("not the gRPC port, 6334"), error.getMessage());
        assertEquals("08S01", ((SQLException) error.getCause()).getSQLState());
    }

    @Test
    void pointsToTheRestPortWhenTheServerDoesNotSpeakHttp() throws Exception {
        try (ServerSocket notHttp = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread answerer = new Thread(() -> answerWithGarbage(notHttp));
            answerer.setDaemon(true);
            answerer.start();

            Properties properties = new Properties();
            properties.setProperty("timeout", "5");

            SQLNonTransientConnectionException error = assertThrows(
                SQLNonTransientConnectionException.class,
                () -> new QdrantDriver().connect("jdbc:qdrant://localhost:" + notHttp.getLocalPort(),
                    properties));

            assertEquals("08001", error.getSQLState());
            assertTrue(error.getMessage().contains("not the gRPC port, 6334"), error.getMessage());
        }
    }

    @Test
    void reportsASlowServerOnConnectAsATimeout() {
        server.delay(Endpoint.VERSION, Duration.ofSeconds(3));

        SQLTimeoutException error = assertThrows(SQLTimeoutException.class,
            () -> new QdrantDriver().connect(server.jdbcUrl("timeout=1"), new Properties()));

        assertEquals("HYT00", error.getSQLState());
    }

    @Test
    void failsToOpenWhenCollectionsCantBeListed() {
        server.fail(Endpoint.COLLECTIONS, 500, "Service internal error: disk full");

        SQLException error = assertThrows(SQLException.class,
            () -> new QdrantDriver().connect(server.jdbcUrl(), new Properties()));

        assertEquals("HY000", error.getSQLState());
        assertTrue(error.getMessage().contains("disk full"), error.getMessage());
    }

    @Test
    void namesTheServerInToString() {
        assertEquals("QdrantConnection[http://localhost:" + server.port() + ", Qdrant "
            + FakeQdrantServer.VERSION + "]", connection.toString());
    }

    // Closing

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
            () -> connection.setReadOnly(true),
            connection::getCatalog,
            () -> connection.setCatalog("c"),
            connection::getSchema,
            () -> connection.setSchema("s"),
            connection::getTypeMap,
            () -> connection.setTypeMap(Map.of()),
            connection::getHoldability,
            () -> connection.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT),
            connection::getTransactionIsolation,
            () -> connection.setTransactionIsolation(Connection.TRANSACTION_NONE),
            () -> connection.getClientInfo("ApplicationName"),
            connection::getClientInfo,
            connection::getNetworkTimeout,
            () -> connection.setNetworkTimeout(Runnable::run, 1));

        for (Executable call : calls) {
            SQLException error = assertThrows(SQLException.class, call);
            assertEquals("08003", error.getSQLState());
        }
    }

    @Test
    void checksValidityAgainstTheServer() throws SQLException {
        assertTrue(connection.isValid(5));

        server.fail(Endpoint.COLLECTIONS, 503, "Service unavailable");
        assertFalse(connection.isValid(5));

        server.recover(Endpoint.COLLECTIONS);
        assertTrue(connection.isValid(0));
    }

    @Test
    void checksValidityWithANewRequestEachTime() throws SQLException {
        int before = server.calls(Endpoint.COLLECTIONS);

        connection.isValid(5);
        connection.isValid(5);

        assertEquals(before + 2, server.calls(Endpoint.COLLECTIONS));
    }

    @Test
    void isNotValidWhenTheApiKeyStopsWorking() throws SQLException {
        server.requireApiKey("rotated");

        assertFalse(connection.isValid(5));
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
        assertEquals(1, tasks.size(), "the gateway is released once");
        assertDoesNotThrow(() -> tasks.get(0).run());
    }

    @Test
    void needsAnExecutorToAbort() {
        assertThrows(SQLException.class, () -> connection.abort(null));
    }

    // Statements

    @Test
    void createsStatementsThatBelongToIt() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertSame(connection, statement.getConnection());
        }
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
    void preparesStatementsInEveryFormButNoCallableOnes() throws SQLException {
        List<PreparedStatement> prepared = List.of(
            connection.prepareStatement("SELECT ?"),
            connection.prepareStatement("SELECT ?", ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY),
            connection.prepareStatement("SELECT ?", ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT),
            connection.prepareStatement("SELECT ?", Statement.RETURN_GENERATED_KEYS),
            connection.prepareStatement("SELECT ?", new int[] {1}),
            connection.prepareStatement("SELECT ?", new String[] {"id"}));

        for (PreparedStatement statement : prepared) {
            assertEquals(1, statement.getParameterMetaData().getParameterCount());
            statement.close();
        }

        List<Executable> calls = List.of(
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
    void warnsThatAPreparedStatementsResultsAreForwardOnly() throws SQLException {
        connection.prepareStatement("SELECT 1", ResultSet.TYPE_SCROLL_INSENSITIVE,
            ResultSet.CONCUR_READ_ONLY).close();

        assertNotNull(connection.getWarnings());
    }

    // Transactions and settings

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
    void keepsReadOnlyAndIgnoresCatalogAndSchemaSettings() throws SQLException {
        assertFalse(connection.isReadOnly());

        connection.setReadOnly(true);
        connection.setCatalog("anything");
        connection.setSchema("anything");

        assertTrue(connection.isReadOnly());
        assertEquals("qdrant", connection.getCatalog());
        assertEquals("collections", connection.getSchema());
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
    void replacesAllClientInfoAtOnce() throws SQLException {
        connection.setClientInfo("ApplicationName", "DataGrip");
        connection.setClientInfo(new Properties());

        assertNull(connection.getClientInfo("ApplicationName"));
    }

    @Test
    void refusesClientInfoOnceClosed() throws SQLException {
        connection.close();

        SQLClientInfoException error = assertThrows(SQLClientInfoException.class,
            () -> connection.setClientInfo("ApplicationName", "DataGrip"));
        assertEquals("08003", error.getSQLState());
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

    // Helpers

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    // Answers like a gRPC port does to HTTP/1.1: with an HTTP/2 frame, then closes
    private static void answerWithGarbage(ServerSocket listener) {
        try (Socket socket = listener.accept(); OutputStream out = socket.getOutputStream()) {
            socket.getInputStream().read(new byte[1024]);
            out.write(new byte[] {0, 0, 18, 4, 0, 0, 0, 0, 0});
            out.write("not http".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException e) {
            // The test is over
        }
    }
}
