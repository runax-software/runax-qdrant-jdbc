package com.runaxsoftware.qdrant.jdbc.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.time.Duration;
import java.util.Properties;

class ConnectionSettingsTest {

    @Test
    void usesDefaultsForAPlainUrl() throws SQLException {
        ConnectionSettings settings = parse("jdbc:qdrant://localhost", new Properties());

        assertEquals("localhost", settings.host());
        assertEquals(6333, settings.port());
        assertFalse(settings.tls());
        assertNull(settings.apiKey());
        assertEquals(Duration.ofSeconds(30), settings.timeout());
        assertEquals(100, settings.sampleSize());
        assertEquals(Duration.ofHours(1), settings.schemaCache());
        assertNull(settings.logFile());
    }

    @Test
    void readsPortAndUrlParameters() throws SQLException {
        ConnectionSettings settings = parse(
            "jdbc:qdrant://db.example.com:7000?tls=true&timeout=5&sampleSize=10&schemaCache=60"
                + "&logFile=/tmp/q.log",
            new Properties());

        assertEquals("db.example.com", settings.host());
        assertEquals(7000, settings.port());
        assertTrue(settings.tls());
        assertEquals(Duration.ofSeconds(5), settings.timeout());
        assertEquals(10, settings.sampleSize());
        assertEquals(Duration.ofSeconds(60), settings.schemaCache());
        assertEquals(Path.of("/tmp/q.log"), settings.logFile());
    }

    @Test
    void readsEverySettingFromProperties() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty(Setting.PASSWORD.key(), "secret");
        properties.setProperty(Setting.TLS.key(), "true");
        properties.setProperty(Setting.TIMEOUT.key(), "12");
        properties.setProperty(Setting.SAMPLE_SIZE.key(), "0");
        properties.setProperty(Setting.SCHEMA_CACHE.key(), "0");
        properties.setProperty(Setting.LOG_FILE.key(), "/tmp/calls.log");

        ConnectionSettings settings = parse("jdbc:qdrant://localhost", properties);

        assertEquals("secret", settings.apiKey());
        assertTrue(settings.tls());
        assertEquals(Duration.ofSeconds(12), settings.timeout());
        assertEquals(0, settings.sampleSize());
        assertEquals(Duration.ZERO, settings.schemaCache());
        assertEquals(Path.of("/tmp/calls.log"), settings.logFile());
    }

    @Test
    void letsPropertiesOverrideUrlParameters() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("timeout", "9");
        properties.setProperty("tls", "false");

        ConnectionSettings settings =
            parse("jdbc:qdrant://localhost?timeout=5&tls=true", properties);

        assertEquals(Duration.ofSeconds(9), settings.timeout());
        assertFalse(settings.tls());
    }

    @Test
    void takesTheApiKeyFromThePasswordProperty() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("password", "secret");

        assertEquals("secret", parse("jdbc:qdrant://localhost", properties).apiKey());
    }

    @Test
    void keepsSpacesInTheApiKey() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("password", " secret ");

        assertEquals(" secret ", parse("jdbc:qdrant://localhost", properties).apiKey());
    }

    @Test
    void treatsAnEmptyPasswordAsNoApiKey() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("password", "");

        assertNull(parse("jdbc:qdrant://localhost", properties).apiKey());
    }

    @Test
    void rejectsAnApiKeyInTheUrl() {
        SQLException error = assertThrows(SQLException.class,
            () -> parse("jdbc:qdrant://localhost?password=secret", new Properties()));

        assertTrue(error.getMessage().contains("password field"));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test
    void acceptsATrailingSlashButNotAPath() throws SQLException {
        assertEquals("localhost", parse("jdbc:qdrant://localhost:6333/", new Properties()).host());
        assertThrows(SQLException.class,
            () -> parse("jdbc:qdrant://localhost:6333/qdrant", new Properties()));
    }

    @Test
    void stripsBracketsFromIpv6Hosts() throws SQLException {
        assertEquals("::1", parse("jdbc:qdrant://[::1]:6333", new Properties()).host());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:qdrant://localhost?tls=yes",
        "jdbc:qdrant://localhost?timeout=0",
        "jdbc:qdrant://localhost?timeout=soon",
        "jdbc:qdrant://localhost?sampleSize=-1",
        "jdbc:qdrant://localhost?sampleSize=10001",
        "jdbc:qdrant://localhost?schemaCache=-1",
        "jdbc:qdrant://localhost?schemaCache=true",
        "jdbc:qdrant:localhost",
        "jdbc:postgresql://localhost/db",
    })
    void rejectsInvalidUrls(String url) {
        SQLNonTransientConnectionException error = assertThrows(
            SQLNonTransientConnectionException.class, () -> parse(url, new Properties()));

        assertEquals("08001", error.getSQLState());
    }

    @Test
    void rejectsInvalidProperties() {
        Properties properties = new Properties();
        properties.setProperty("sampleSize", "lots");

        SQLException error = assertThrows(SQLException.class,
            () -> parse("jdbc:qdrant://localhost", properties));

        assertEquals("08001", error.getSQLState());
        assertTrue(error.getMessage().contains("sampleSize"), error.getMessage());
    }

    @Test
    void decodesUrlParameters() throws SQLException {
        ConnectionSettings settings = parse(
            "jdbc:qdrant://localhost?logFile=%2Ftmp%2Fqdrant%20calls.log", new Properties());

        assertEquals(Path.of("/tmp/qdrant calls.log"), settings.logFile());
    }

    @Test
    void ignoresEmptyAndUnknownParameters() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "someone");
        properties.setProperty("ApplicationName", "DataGrip");

        ConnectionSettings settings = parse("jdbc:qdrant://localhost?&tls&unknown=1&&", properties);

        assertFalse(settings.tls());
        assertEquals("localhost", settings.host());
    }

    @Test
    void readsSettingsLeniently() throws SQLException {
        ConnectionSettings settings = parse(
            "JDBC:QDRANT://LocalHost:6333?tls=TRUE&timeout=%207%20", new Properties());

        assertEquals("LocalHost", settings.host());
        assertTrue(settings.tls());
        assertEquals(Duration.ofSeconds(7), settings.timeout());
    }

    @Test
    void acceptsTheSampleSizeLimits() throws SQLException {
        assertEquals(0, parse("jdbc:qdrant://localhost?sampleSize=0", new Properties()).sampleSize());
        assertEquals(10_000,
            parse("jdbc:qdrant://localhost?sampleSize=10000", new Properties()).sampleSize());
    }

    @Test
    void acceptsAnyPositiveTimeout() throws SQLException {
        assertEquals(Duration.ofSeconds(1),
            parse("jdbc:qdrant://localhost?timeout=1", new Properties()).timeout());
        assertEquals(Duration.ofSeconds(Integer.MAX_VALUE),
            parse("jdbc:qdrant://localhost?timeout=" + Integer.MAX_VALUE, new Properties())
                .timeout());
    }

    @Test
    void treatsBlankPropertiesAsUnset() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("timeout", " ");
        properties.setProperty("logFile", "");
        properties.setProperty("tls", "");

        ConnectionSettings settings = parse("jdbc:qdrant://localhost", properties);

        assertEquals(Duration.ofSeconds(30), settings.timeout());
        assertNull(settings.logFile());
        assertFalse(settings.tls());
    }

    @Test
    void reportsInvalidSettingsAsConnectionErrors() {
        SQLException error = assertThrows(SQLException.class,
            () -> parse("jdbc:qdrant://localhost?timeout=0", new Properties()));

        assertEquals("08001", error.getSQLState());
        assertTrue(error.getMessage().contains("timeout"), error.getMessage());
    }

    @Test
    void showsAnExampleForUrlsWithoutAHost() {
        SQLException error = assertThrows(SQLException.class,
            () -> parse("jdbc:qdrant:///", new Properties()));

        assertTrue(error.getMessage().contains("jdbc:qdrant://localhost:6333"), error.getMessage());
    }

    @Test
    void keepsTheApiKeyOutOfToString() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("password", "secret");

        String text = parse("jdbc:qdrant://localhost", properties).toString();

        assertFalse(text.contains("secret"), text);
        assertTrue(text.contains("apiKey=set"), text);
    }

    @Test
    void saysWhenThereIsNoApiKeyInToString() throws SQLException {
        String text = parse("jdbc:qdrant://localhost", new Properties()).toString();

        assertTrue(text.startsWith("ConnectionSettings["), text);
        assertTrue(text.contains("apiKey=none"), text);
        assertTrue(text.contains("host=localhost"), text);
        assertTrue(text.contains("port=6333"), text);
        assertTrue(text.contains("schemaCache=3600s"), text);
    }

    private static ConnectionSettings parse(String url, Properties properties)
        throws SQLException {
        return ConnectionSettings.parse(url, properties);
    }
}
