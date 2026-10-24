package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;

class QdrantDriverTest {

    private final QdrantDriver driver = new QdrantDriver();

    @Test
    void acceptsOnlyQdrantUrls() throws SQLException {
        assertTrue(driver.acceptsURL("jdbc:qdrant://localhost:6334"));
        assertTrue(driver.acceptsURL("JDBC:QDRANT://localhost"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://localhost/db"));
        assertThrows(SQLException.class, () -> driver.acceptsURL(null));
    }

    @Test
    void returnsNullForOtherUrls() throws SQLException {
        assertNull(driver.connect("jdbc:postgresql://localhost/db", new Properties()));
    }

    @Test
    void rejectsAMalformedQdrantUrl() {
        assertThrows(SQLException.class, () -> driver.connect("jdbc:qdrant:nohost", null));
    }

    @Test
    void isRegisteredWithDriverManager() throws SQLException {
        assertInstanceOf(QdrantDriver.class, DriverManager.getDriver("jdbc:qdrant://localhost"));
    }

    @Test
    void isListedAsAServiceProvider() {
        List<String> drivers = ServiceLoader.load(Driver.class).stream()
            .map(provider -> provider.type().getName())
            .toList();

        assertTrue(drivers.contains(QdrantDriver.class.getName()), drivers.toString());
    }

    @Test
    void connectsAndReturnsAQdrantConnection() throws Exception {
        try (FakeQdrantServer server = FakeQdrantServer.start();
             Connection connection = driver.connect(server.jdbcUrl(), null)) {
            assertInstanceOf(QdrantConnection.class, connection);
        }
    }

    @Test
    void connectsThroughDriverManager() throws Exception {
        try (FakeQdrantServer server = FakeQdrantServer.start();
             Connection connection = DriverManager.getConnection(server.jdbcUrl(), "", "")) {
            assertFalse(connection.isClosed());
        }
    }

    @Test
    void sendsThePasswordAsTheApiKey() throws Exception {
        try (FakeQdrantServer server = FakeQdrantServer.start()) {
            server.requireApiKey("secret");
            Properties properties = new Properties();
            properties.setProperty("password", "secret");

            try (Connection connection = driver.connect(server.jdbcUrl(), properties)) {
                assertTrue(connection.isValid(5));
            }

            assertTrue(server.apiKeys().stream().allMatch("secret"::equals));
        }
    }

    @Test
    void reportsAWrongApiKeyOnConnect() throws Exception {
        try (FakeQdrantServer server = FakeQdrantServer.start()) {
            server.requireApiKey("secret");
            Properties properties = new Properties();
            properties.setProperty("password", "wrong");

            SQLInvalidAuthorizationSpecException error = assertThrows(
                SQLInvalidAuthorizationSpecException.class,
                () -> driver.connect(server.jdbcUrl(), properties));

            assertEquals("28000", error.getSQLState());
        }
    }

    @Test
    void reportsAMissingApiKeyOnConnect() throws Exception {
        try (FakeQdrantServer server = FakeQdrantServer.start()) {
            server.requireApiKey("secret");

            assertThrows(SQLInvalidAuthorizationSpecException.class,
                () -> driver.connect(server.jdbcUrl(), new Properties()));
        }
    }

    @Test
    void reportsAConnectionErrorWhenNothingListens() {
        Properties properties = new Properties();
        properties.setProperty("timeout", "5");

        SQLNonTransientConnectionException error = assertThrows(
            SQLNonTransientConnectionException.class,
            () -> driver.connect("jdbc:qdrant://localhost:1", properties));

        assertEquals("08001", error.getSQLState());
        assertTrue(error.getMessage().contains("localhost:1"));
    }

    @Test
    void pointsToTheGrpcPortWhenSomethingElseAnswers() throws Exception {
        try (FakeQdrantServer server = FakeQdrantServer.start()) {
            server.fail("HealthCheck", io.grpc.Status.INTERNAL.withDescription("http2 exception"));

            SQLNonTransientConnectionException error = assertThrows(
                SQLNonTransientConnectionException.class,
                () -> driver.connect(server.jdbcUrl(), new Properties()));

            assertEquals("08001", error.getSQLState());
            assertTrue(error.getMessage().contains("http2 exception"), error.getMessage());
            assertTrue(error.getMessage().contains("gRPC port, 6334 by default"), error.getMessage());
            assertEquals("HY000", ((SQLException) error.getCause()).getSQLState());
        }
    }

    @Test
    void reportsASlowServerOnConnectAsATimeout() throws Exception {
        try (FakeQdrantServer server = FakeQdrantServer.start()) {
            server.delay("HealthCheck", java.time.Duration.ofSeconds(3));

            assertThrows(SQLTimeoutException.class,
                () -> driver.connect(server.jdbcUrl("timeout=1"), new Properties()));
        }
    }

    @Test
    void describesItsProperties() {
        DriverPropertyInfo[] properties = driver.getPropertyInfo("jdbc:qdrant://localhost", null);

        List<String> names = Arrays.stream(properties).map(property -> property.name).toList();
        assertEquals(List.of("password", "tls", "timeout", "sampleSize", "logFile"), names);
        assertNull(properties[0].value);
        assertEquals("false", properties[1].value);
        assertArrayEquals(new String[] {"false", "true"}, properties[1].choices);
        assertEquals("30", properties[2].value);
        assertEquals("100", properties[3].value);
        assertNull(properties[4].value);

        for (DriverPropertyInfo property : properties) {
            assertFalse(property.required);
            assertFalse(property.description.isBlank());
        }
    }

    @Test
    void showsTheCurrentValuesButNeverThePassword() {
        Properties current = new Properties();
        current.setProperty("password", "secret");
        current.setProperty("tls", "true");
        current.setProperty("timeout", "7");
        current.setProperty("sampleSize", "5");
        current.setProperty("logFile", "/tmp/calls.log");

        DriverPropertyInfo[] properties = driver.getPropertyInfo("jdbc:qdrant://localhost", current);

        assertNull(properties[0].value);
        assertEquals("true", properties[1].value);
        assertEquals("7", properties[2].value);
        assertEquals("5", properties[3].value);
        assertEquals("/tmp/calls.log", properties[4].value);
    }

    @Test
    void isNotJdbcCompliantAndNamesItsLogger() {
        assertFalse(driver.jdbcCompliant());
        assertEquals("com.runaxsoftware.qdrant.jdbc", driver.getParentLogger().getName());
    }

    @Test
    void reportsTheBuildVersion() {
        assertFalse(DriverInfo.VERSION.contains("$"));
        assertFalse(DriverInfo.VERSION.equals("0.0.0"));
        assertEquals(DriverInfo.MAJOR_VERSION, driver.getMajorVersion());
        assertEquals(DriverInfo.MINOR_VERSION, driver.getMinorVersion());
        assertEquals("runax-qdrant-jdbc/" + DriverInfo.VERSION, DriverInfo.USER_AGENT);
    }

    @Test
    void splitsVersionNumbers() {
        assertEquals(1, DriverInfo.versionPart("1.20.0", 0));
        assertEquals(20, DriverInfo.versionPart("1.20.0", 1));
        assertEquals(1, DriverInfo.versionPart("0.1.0-SNAPSHOT", 1));
        assertEquals(0, DriverInfo.versionPart("dev", 0));
        assertEquals(0, DriverInfo.versionPart("1", 1));
    }

    @Test
    void failsWhenTheCallLogCantBeWritten(@TempDir Path directory) {
        Properties properties = new Properties();
        properties.setProperty("logFile", directory.resolve("missing/calls.log").toString());

        SQLException error = assertThrows(SQLException.class,
            () -> driver.connect("jdbc:qdrant://localhost:1", properties));

        assertTrue(error.getMessage().contains("Can't write the JDBC call log"), error.getMessage());
    }

    @Test
    void logsAFailedConnect(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("calls.log");
        Properties properties = new Properties();
        properties.setProperty("logFile", log.toString());
        properties.setProperty("timeout", "5");

        assertThrows(SQLNonTransientConnectionException.class,
            () -> driver.connect("jdbc:qdrant://localhost:1", properties));

        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(text.contains("connect jdbc:qdrant://localhost:1"), text);
        assertTrue(text.contains("connect failed: java.sql.SQLNonTransientConnectionException"), text);
    }
}
