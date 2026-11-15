package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.connection.QdrantConnection;
import com.runaxsoftware.qdrant.jdbc.connection.Setting;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;

class QdrantDriverTest {

    private final QdrantDriver driver = new QdrantDriver();

    // URLs

    @Test
    void acceptsOnlyQdrantUrls() throws SQLException {
        assertTrue(driver.acceptsURL("jdbc:qdrant://localhost:6333"));
        assertTrue(driver.acceptsURL("JDBC:QDRANT://localhost"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://localhost/db"));
        assertThrows(SQLException.class, () -> driver.acceptsURL(null));
    }

    @Test
    void returnsNullForOtherUrls() throws SQLException {
        assertNull(driver.connect("jdbc:postgresql://localhost/db", new Properties()));
        assertNull(driver.connect("jdbc:postgresql://localhost/db", null));
    }

    @Test
    void rejectsAMalformedQdrantUrl() {
        SQLNonTransientConnectionException error = assertThrows(
            SQLNonTransientConnectionException.class,
            () -> driver.connect("jdbc:qdrant:nohost", null));

        assertEquals("08001", error.getSQLState());
    }

    @Test
    void rejectsInvalidSettingsBeforeConnecting() {
        SQLException error = assertThrows(SQLException.class,
            () -> driver.connect("jdbc:qdrant://localhost:1?timeout=never", new Properties()));

        assertEquals("08001", error.getSQLState());
        assertTrue(error.getMessage().startsWith("timeout must be a whole number"),
            error.getMessage());
    }

    // Registration

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

    // Connecting

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
            assertTrue(server.apiKeys().stream().allMatch(String::isEmpty), "no API key sent");
        }
    }

    @Test
    void sendsThePasswordAsTheApiKey() throws Exception {
        try (FakeQdrantServer server = FakeQdrantServer.start()) {
            server.requireApiKey("secret");

            try (Connection connection =
                     DriverManager.getConnection(server.jdbcUrl(), "ignored-user", "secret")) {
                assertTrue(connection.isValid(5));
            }

            assertTrue(server.apiKeys().stream().allMatch("secret"::equals),
                server.apiKeys().toString());
        }
    }

    @Test
    void readsSettingsFromTheUrl() throws Exception {
        try (FakeQdrantServer server = FakeQdrantServer.start();
             Connection connection = driver.connect(server.jdbcUrl("timeout=5&sampleSize=0"),
                 new Properties())) {
            assertTrue(connection.isValid(1));
        }
    }

    // Properties

    @Test
    void describesItsProperties() {
        DriverPropertyInfo[] properties = driver.getPropertyInfo("jdbc:qdrant://localhost", null);

        List<String> names = Arrays.stream(properties).map(property -> property.name).toList();
        assertEquals(List.of("password", "tls", "timeout", "sampleSize", "schemaCache",
            "defaultLimit", "logFile", "openaiApiKey", "openaiUrl", "voyageApiKey", "voyageUrl",
            "localFiles"),
            names);
        assertNull(properties[0].value);
        assertEquals("false", properties[1].value);
        assertArrayEquals(new String[] {"false", "true"}, properties[1].choices);
        assertEquals("30", properties[2].value);
        assertEquals("100", properties[3].value);
        assertEquals("3600", properties[4].value);
        assertEquals("1000", properties[5].value);
        assertNull(properties[6].value, "logFile has no default");
        assertEquals("https://api.openai.com/v1", properties[8].value);
        assertEquals("https://api.voyageai.com/v1", properties[10].value);

        for (DriverPropertyInfo property : properties) {
            assertFalse(property.required);
            assertFalse(property.description.isBlank());
        }
    }

    @Test
    void describesEverySettingWithItsKeyDescriptionAndChoices() {
        DriverPropertyInfo[] properties = driver.getPropertyInfo(null, new Properties());

        assertEquals(Setting.values().length, properties.length);

        for (int i = 0; i < properties.length; i++) {
            Setting setting = Setting.values()[i];

            assertEquals(setting.key(), properties[i].name);
            assertEquals(setting.description(), properties[i].description);
            assertArrayEquals(setting.choices().toArray(String[]::new), properties[i].choices);
        }
    }

    @Test
    void showsTheCurrentValuesButNeverThePassword() {
        Properties current = new Properties();
        current.setProperty("password", "secret");
        current.setProperty("tls", "true");
        current.setProperty("timeout", "7");
        current.setProperty("sampleSize", "5");
        current.setProperty("schemaCache", "60");
        current.setProperty("logFile", "/tmp/calls.log");
        current.setProperty("openaiApiKey", "secret");
        current.setProperty("voyageApiKey", "secret");

        DriverPropertyInfo[] properties = driver.getPropertyInfo("jdbc:qdrant://localhost", current);

        assertNull(properties[0].value);
        assertEquals("true", properties[1].value);
        assertEquals("7", properties[2].value);
        assertEquals("5", properties[3].value);
        assertEquals("60", properties[4].value);
        assertEquals("1000", properties[5].value, "the default limit nothing set");
        assertEquals("/tmp/calls.log", properties[6].value);
        assertNull(properties[7].value, "an embedding provider's key is never shown");
        assertNull(properties[9].value);

        for (DriverPropertyInfo property : properties) {
            assertFalse("secret".equals(property.value), property.name);
        }
    }

    // About the driver

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
        assertEquals("Runax JDBC Driver for Qdrant", DriverInfo.NAME);
    }

    @Test
    void splitsVersionNumbers() {
        assertEquals(1, DriverInfo.versionPart("1.20.0", 0));
        assertEquals(20, DriverInfo.versionPart("1.20.0", 1));
        assertEquals(1, DriverInfo.versionPart("0.1.0-SNAPSHOT", 1));
        assertEquals(0, DriverInfo.versionPart("dev", 0));
        assertEquals(0, DriverInfo.versionPart("1", 1));
    }

    // The call log

    @Test
    void failsWhenTheCallLogCantBeWritten(@TempDir Path directory) {
        Properties properties = new Properties();
        properties.setProperty("logFile", directory.resolve("missing/calls.log").toString());

        SQLException error = assertThrows(SQLException.class,
            () -> driver.connect("jdbc:qdrant://localhost:1", properties));

        assertTrue(error.getMessage().contains("Can't write the JDBC call log"), error.getMessage());
        assertTrue(error.getMessage().contains("its folder doesn't exist"), error.getMessage());
    }

    @Test
    void logsAFailedConnect(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("calls.log");
        String url = "jdbc:qdrant://localhost:" + freePort();
        Properties properties = new Properties();
        properties.setProperty("logFile", log.toString());
        properties.setProperty("timeout", "5");

        assertThrows(SQLNonTransientConnectionException.class, () -> driver.connect(url, properties));

        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(text.contains("connect " + url), text);
        assertTrue(text.contains("connect failed: java.sql.SQLNonTransientConnectionException"), text);
    }

    @Test
    void keepsTheApiKeyOutOfTheLogOfAFailedConnect(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("calls.log");
        Properties properties = new Properties();
        properties.setProperty("logFile", log.toString());
        properties.setProperty("timeout", "5");
        properties.setProperty("password", "super-secret-key");

        assertThrows(SQLException.class,
            () -> driver.connect("jdbc:qdrant://localhost:" + freePort(), properties));

        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertFalse(text.contains("super-secret-key"), text);
        assertTrue(text.contains("apiKey=set"), text);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
