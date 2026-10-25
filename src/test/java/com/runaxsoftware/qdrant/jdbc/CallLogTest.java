package com.runaxsoftware.qdrant.jdbc;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import io.qdrant.client.grpc.Collections.Distance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class CallLogTest {

    @TempDir
    Path directory;

    private FakeQdrantServer server;
    private Path log;

    @BeforeEach
    void start() throws Exception {
        server = FakeQdrantServer.start()
            .collection("products", FakeQdrantServer.unnamedVector(2, Distance.Cosine, Map.of()),
                List.of(FakeQdrantServer.point(id(1), Map.of("name", value("Lamp")))));
        log = directory.resolve("calls.log");
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    @Test
    void logsCallsResultsAndTheObjectsTheyReturn() throws Exception {
        try (Connection connection = connect(new Properties());
             Statement statement = connection.createStatement()) {
            statement.setMaxRows(10);
            DatabaseMetaData metaData = connection.getMetaData();

            try (ResultSet tables = metaData.getTables(null, null, "%", null)) {
                tables.next();
                tables.getString(3);
            }
        }

        String text = text();
        assertTrue(text.contains("connect " + server.jdbcUrl() + " ConnectionSettings["), text);
        assertTrue(text.contains("Connection.createStatement() = Statement"), text);
        assertTrue(text.contains("Statement.setMaxRows(10)"), text);
        assertTrue(text.contains("Connection.getMetaData() = DatabaseMetaData"), text);
        assertTrue(text.contains("DatabaseMetaData.getTables(null, null, \"%\", null) = ResultSet"), text);
        assertTrue(text.contains("ResultSet.next() = true"), text);
        assertTrue(text.contains("ResultSet.getString(3) = \"products\""), text);
        assertTrue(text.contains("ResultSet.close()"), text);
        assertTrue(text.contains("Statement.close()"), text);
        assertTrue(text.contains("Connection.close()"), text);
    }

    @Test
    void startsEachLineWithATimeAndThread() throws Exception {
        connect(new Properties()).close();

        for (String line : lines()) {
            assertTrue(line.matches("\\d{4}-\\d{2}-\\d{2}T\\S+Z \\[[^]]+] .+"), line);
        }
    }

    @Test
    void logsAndRethrowsExceptions() throws Exception {
        try (Connection connection = connect(new Properties());
             Statement statement = connection.createStatement()) {
            assertThrows(SQLFeatureNotSupportedException.class,
                () -> statement.executeQuery("SELECT * FROM products"));
        }

        assertTrue(text().contains(
            "Statement.executeQuery(\"SELECT * FROM products\") threw java.sql.SQLFeatureNotSupportedException"),
            text());
    }

    @Test
    void shortensLongValues() throws Exception {
        String sql = "SELECT " + "x".repeat(500);

        try (Connection connection = connect(new Properties());
             Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeQuery(sql));
        }

        String line = lines().stream().filter(l -> l.contains("executeQuery")).findFirst()
            .orElseThrow();
        assertFalse(line.contains(sql));
        assertTrue(line.contains("x".repeat(100) + "...\""), line);
    }

    @Test
    void neverLogsTheApiKey() throws Exception {
        server.requireApiKey("super-secret-key");
        Properties properties = new Properties();
        properties.setProperty("password", "super-secret-key");

        try (Connection connection = connect(properties)) {
            connection.getMetaData().getTables(null, null, "%", null).close();
        }

        assertFalse(text().contains("super-secret-key"));
        assertTrue(text().contains("apiKey=set"), text());
    }

    @Test
    void logsPropertiesWithoutTheirValues() throws Exception {
        Properties info = new Properties();
        info.setProperty("ApplicationName", "private-value");
        info.setProperty("ClientUser", "someone");

        try (Connection connection = connect(new Properties())) {
            connection.setClientInfo(info);
        }

        assertTrue(text().contains("Connection.setClientInfo(Properties[2])"), text());
        assertFalse(text().contains("private-value"));
    }

    @Test
    void logsArraysElementByElement() throws Exception {
        try (Connection connection = connect(new Properties())) {
            connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE", "VIEW"})
                .close();
        }

        assertTrue(text().contains("getTables(null, null, \"%\", [\"TABLE\", \"VIEW\"])"), text());
    }

    @Test
    void handlesObjectMethodsWithoutLogging() throws Exception {
        try (Connection connection = connect(new Properties())) {
            assertTrue(connection.toString().startsWith("QdrantConnection["));
            assertEquals(connection, connection);
            assertNotEquals(connection, new Object());
            assertEquals(System.identityHashCode(connection), connection.hashCode());
        }

        assertFalse(text().contains("toString"));
        assertFalse(text().contains("hashCode"));
        assertFalse(text().contains("equals"));
    }

    @Test
    void unwrapsToTheRealConnection() throws Exception {
        try (Connection connection = connect(new Properties())) {
            assertInstanceOf(QdrantConnection.class, connection.unwrap(QdrantConnection.class));
            assertTrue(connection.isWrapperFor(QdrantConnection.class));
        }
    }

    @Test
    void stopsLoggingWhenTheConnectionCloses() throws Exception {
        Connection connection = connect(new Properties());
        Statement statement = connection.createStatement();
        connection.close();
        int lines = lines().size();

        statement.isClosed();

        assertEquals(lines, lines().size());
    }

    @Test
    void appendsToAnExistingLog() throws Exception {
        Files.writeString(log, "earlier line\n", StandardCharsets.UTF_8);

        connect(new Properties()).close();

        assertEquals("earlier line", lines().get(0));
        assertTrue(lines().size() > 1);
    }

    private Connection connect(Properties properties) throws SQLException {
        properties.setProperty("logFile", log.toString());
        return new QdrantDriver().connect(server.jdbcUrl(), properties);
    }

    private String text() throws Exception {
        return Files.readString(log, StandardCharsets.UTF_8);
    }

    private List<String> lines() throws Exception {
        return Files.readAllLines(log, StandardCharsets.UTF_8);
    }
}
