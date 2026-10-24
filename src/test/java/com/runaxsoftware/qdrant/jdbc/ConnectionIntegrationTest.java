package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Connects to a real Qdrant and goes through what a tool does on every connection. Needs
 * Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class ConnectionIntegrationTest {

    private static final String COMPOSE_URL = "jdbc:qdrant://localhost:6334";

    @Test
    void acceptsTheSettingsToolsApplyOnConnect() throws SQLException {
        try (Connection connection = connect(TestQdrant.properties())) {
            assertDoesNotThrow(() -> {
                connection.setAutoCommit(true);
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setCatalog(null);
                connection.setSchema(null);
                connection.clearWarnings();
                connection.commit();
                connection.rollback();
            });
            assertTrue(connection.getAutoCommit());
            assertEquals(Connection.TRANSACTION_NONE, connection.getTransactionIsolation());

            try (Statement statement = connection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
                statement.setMaxRows(500);
                statement.setLargeMaxRows(500);
                statement.setFetchSize(100);
                statement.setQueryTimeout(30);
                assertEquals(500, statement.getLargeMaxRows());
                assertEquals(-1, statement.getLargeUpdateCount());
            }

            assertTrue(connection.getWarnings() != null, "downgraded result set type is reported");
        }
    }

    @Test
    void refusesToRunSqlForNow() throws SQLException {
        try (Connection connection = connect(TestQdrant.properties());
             Statement statement = connection.createStatement()) {
            assertThrows(SQLFeatureNotSupportedException.class,
                () -> statement.executeQuery("SELECT * FROM products"));
            assertThrows(SQLFeatureNotSupportedException.class,
                () -> connection.prepareStatement("SELECT 1"));
        }
    }

    @Test
    void checksValidityAgainstTheServer() throws SQLException {
        Connection connection = connect(TestQdrant.properties());

        assertTrue(connection.isValid(5));
        connection.close();

        assertTrue(connection.isClosed());
        assertFalse(connection.isValid(5));
        assertEquals("08003",
            assertThrows(SQLException.class, connection::createStatement).getSQLState());
    }

    @Test
    void reportsTheServerVersion() throws SQLException {
        try (Connection connection = connect(TestQdrant.properties())) {
            DatabaseMetaData metaData = connection.getMetaData();
            String version = metaData.getDatabaseProductVersion();

            assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"), version);
            assertEquals(Integer.parseInt(version.split("\\.")[0]), metaData.getDatabaseMajorVersion());
            assertEquals(Integer.parseInt(version.split("\\.")[1]), metaData.getDatabaseMinorVersion());
        }
    }

    @Test
    void appliesUrlParameters() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            TestQdrant.jdbcUrl() + (TestQdrant.jdbcUrl().contains("?") ? "&" : "?")
                + "timeout=10&sampleSize=5", TestQdrant.properties())) {
            assertTrue(connection.isValid(5));
        }
    }

    @Test
    void pointsToTheGrpcPortWhenGivenTheRestPort() {
        assumeTrue(TestQdrant.jdbcUrl().equals(COMPOSE_URL), "needs the compose.yml server");

        SQLNonTransientConnectionException error = assertThrows(
            SQLNonTransientConnectionException.class,
            () -> DriverManager.getConnection("jdbc:qdrant://localhost:6333", new Properties()));

        assertEquals("08001", error.getSQLState());
        assertTrue(error.getMessage().contains("gRPC port, 6334 by default, not the REST port"),
            error.getMessage());
    }

    @Test
    void reportsTlsAgainstAPlainServerAsAConnectionError() {
        assumeTrue(TestQdrant.jdbcUrl().equals(COMPOSE_URL), "needs the compose.yml server");

        SQLNonTransientConnectionException error = assertThrows(
            SQLNonTransientConnectionException.class,
            () -> DriverManager.getConnection(COMPOSE_URL + "?tls=true&timeout=10", new Properties()));

        assertEquals("08001", error.getSQLState());
        assertFalse(error.getMessage().contains("\n"), error.getMessage());
        assertFalse(error.getMessage().contains("Channel Pipeline"), error.getMessage());
    }

    @Test
    void reportsAnUnknownHost() {
        SQLNonTransientConnectionException error = assertThrows(
            SQLNonTransientConnectionException.class,
            () -> DriverManager.getConnection("jdbc:qdrant://no-such-host.invalid:6334?timeout=10",
                new Properties()));

        assertTrue(error.getMessage().contains("no-such-host.invalid"), error.getMessage());
    }

    @Test
    void handlesManyConnectionsAtOnce() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < 16; i++) {
            tasks.add(() -> {
                try (Connection connection = connect(TestQdrant.properties());
                     ResultSet tables = connection.getMetaData().getTables(null, null, "%", null)) {
                    int count = 0;

                    while (tables.next()) {
                        count++;
                    }

                    assertTrue(connection.isValid(5));
                    return count;
                }
            });
        }

        for (Future<Integer> result : runAll(tasks)) {
            assertTrue(result.get() >= 0);
        }
    }

    @Test
    void sharesOneConnectionAcrossThreads() throws Exception {
        try (Connection connection = connect(TestQdrant.properties())) {
            DatabaseMetaData metaData = connection.getMetaData();
            List<Callable<Integer>> tasks = new ArrayList<>();

            for (int i = 0; i < 16; i++) {
                tasks.add(() -> {
                    try (ResultSet types = metaData.getTypeInfo();
                         ResultSet tables = metaData.getTables(null, null, "%", null)) {
                        int count = 0;

                        while (types.next()) {
                            count++;
                        }

                        while (tables.next()) {
                            count++;
                        }

                        return count;
                    }
                });
            }

            for (Future<Integer> result : runAll(tasks)) {
                assertTrue(result.get() > 0);
            }

            assertTrue(connection.isValid(5));
        }
    }

    @Test
    void opensAndClosesConnectionsRepeatedly() throws SQLException {
        for (int i = 0; i < 30; i++) {
            Connection connection = connect(TestQdrant.properties());
            connection.getMetaData().getTableTypes().close();
            connection.close();
            assertTrue(connection.isClosed());
        }
    }

    @Test
    void logsCallsWithoutTheApiKey(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("calls.log");
        Properties properties = TestQdrant.properties();
        properties.putIfAbsent(ConnectionSettings.PASSWORD, "key-the-local-server-ignores");
        String apiKey = properties.getProperty(ConnectionSettings.PASSWORD);
        properties.setProperty(ConnectionSettings.LOG_FILE, log.toString());

        try (Connection connection = connect(properties);
             ResultSet tables = connection.getMetaData().getTables(null, null, "%", null)) {
            tables.next();
        }

        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(text.contains("connect " + TestQdrant.jdbcUrl()), text);
        assertTrue(text.contains("DatabaseMetaData.getTables(null, null, \"%\", null) = ResultSet"),
            text);
        assertTrue(text.contains("ResultSet.next()"), text);
        assertTrue(text.contains("Connection.close()"), text);
        assertFalse(text.contains(apiKey), "the API key must not be logged");
    }

    private static Connection connect(Properties properties) throws SQLException {
        return DriverManager.getConnection(TestQdrant.jdbcUrl(), properties);
    }

    private static <T> List<Future<T>> runAll(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService threads = Executors.newFixedThreadPool(8);

        try {
            return threads.invokeAll(tasks, 60, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }
    }
}
