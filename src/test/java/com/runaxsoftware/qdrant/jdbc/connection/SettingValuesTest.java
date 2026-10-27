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
import java.util.List;
import java.util.Map;
import java.util.Properties;

class SettingValuesTest {

    // Merging

    @Test
    void readsUrlParameters() throws SQLException {
        assertEquals(5, values(Map.of("timeout", "5")).wholeNumber(Setting.TIMEOUT, 1, 100));
    }

    @Test
    void letsPropertiesOverrideUrlParameters() throws SQLException {
        Properties properties = properties("timeout", "9");

        assertEquals(9, SettingValues.merge(Map.of("timeout", "5"), properties)
            .wholeNumber(Setting.TIMEOUT, 1, 100));
    }

    @Test
    void acceptsNoProperties() throws SQLException {
        assertTrue(SettingValues.merge(Map.of("tls", "true"), null).bool(Setting.TLS));
    }

    @Test
    void readsPropertyDefaults() throws SQLException {
        Properties defaults = properties("tls", "true");
        Properties properties = new Properties(defaults);

        assertTrue(SettingValues.merge(Map.of(), properties).bool(Setting.TLS));
    }

    // Booleans

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", " true "})
    void readsTrueInAnyCase(String value) throws SQLException {
        assertTrue(values(Map.of("tls", value)).bool(Setting.TLS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "", " "})
    void readsFalseOrTheDefault(String value) throws SQLException {
        assertFalse(values(Map.of("tls", value)).bool(Setting.TLS));
    }

    @Test
    void usesTheDefaultForAMissingBoolean() throws SQLException {
        assertFalse(values(Map.of()).bool(Setting.TLS));
    }

    @Test
    void rejectsOtherBooleans() {
        SQLException error = invalid(() -> values(Map.of("tls", "yes")).bool(Setting.TLS));

        assertEquals("tls must be true or false, not yes", error.getMessage());
    }

    // Whole numbers

    @Test
    void readsWholeNumbersWithinTheRange() throws SQLException {
        assertEquals(0, values(Map.of("sampleSize", "0")).wholeNumber(Setting.SAMPLE_SIZE, 0, 10));
        assertEquals(10, values(Map.of("sampleSize", "10")).wholeNumber(Setting.SAMPLE_SIZE, 0, 10));
        assertEquals(7, values(Map.of("timeout", " 7 ")).wholeNumber(Setting.TIMEOUT, 1, 10));
    }

    @Test
    void usesTheDefaultForAMissingOrBlankNumber() throws SQLException {
        assertEquals(30, values(Map.of()).wholeNumber(Setting.TIMEOUT, 1, 100));
        assertEquals(30, values(Map.of("timeout", "  ")).wholeNumber(Setting.TIMEOUT, 1, 100));
        assertEquals(100, values(Map.of()).wholeNumber(Setting.SAMPLE_SIZE, 0, 10_000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "soon", "1.5", "99999999999"})
    void rejectsNumbersOutsideTheRangeOrNotWhole(String value) {
        SQLException error = invalid(
            () -> values(Map.of("timeout", value)).wholeNumber(Setting.TIMEOUT, 1, 100));

        assertEquals("timeout must be a whole number from 1 to 100, not " + value,
            error.getMessage());
    }

    // Paths

    @Test
    void readsPaths() throws SQLException {
        assertEquals(Path.of("/tmp/calls.log"),
            values(Map.of("logFile", " /tmp/calls.log ")).path(Setting.LOG_FILE));
    }

    @Test
    void readsAMissingOrBlankPathAsNone() throws SQLException {
        assertNull(values(Map.of()).path(Setting.LOG_FILE));
        assertNull(values(Map.of("logFile", " ")).path(Setting.LOG_FILE));
    }

    @Test
    void rejectsInvalidPaths() {
        SQLException error = invalid(
            () -> values(Map.of("logFile", "calls\u0000.log")).path(Setting.LOG_FILE));

        assertTrue(error.getMessage().startsWith("logFile isn't a valid path"), error.getMessage());
    }

    // Secrets

    @Test
    void readsSecretsExactlyAsGiven() {
        assertEquals(" key with spaces ",
            values(Map.of("password", " key with spaces ")).secret(Setting.PASSWORD));
    }

    @Test
    void readsAMissingOrEmptySecretAsNone() {
        assertNull(values(Map.of()).secret(Setting.PASSWORD));
        assertNull(values(Map.of("password", "")).secret(Setting.PASSWORD));
    }

    // The settings themselves

    @Test
    void listsTheSettingsInTheOrderToolsShowThem() {
        assertEquals(List.of("password", "tls", "timeout", "sampleSize", "schemaCache", "logFile"),
            List.of(Setting.values()).stream().map(Setting::key).toList());
    }

    @Test
    void hasDefaultsForEverySettingButTheKeyAndTheLog() {
        assertNull(Setting.PASSWORD.defaultValue());
        assertEquals("false", Setting.TLS.defaultValue());
        assertEquals("30", Setting.TIMEOUT.defaultValue());
        assertEquals("100", Setting.SAMPLE_SIZE.defaultValue());
        assertEquals("3600", Setting.SCHEMA_CACHE.defaultValue());
        assertNull(Setting.LOG_FILE.defaultValue());
    }

    @Test
    void offersChoicesOnlyForTls() {
        assertEquals(List.of("false", "true"), Setting.TLS.choices());

        for (Setting setting : Setting.values()) {
            if (setting != Setting.TLS) {
                assertEquals(List.of(), setting.choices(), setting.key());
            }
        }
    }

    @Test
    void describesEverySetting() {
        for (Setting setting : Setting.values()) {
            assertFalse(setting.description().isBlank(), setting.key());
        }

        assertTrue(Setting.LOG_FILE.description().contains("API key is never logged"));
    }

    private static SettingValues values(Map<String, String> urlParameters) {
        return SettingValues.merge(urlParameters, new Properties());
    }

    private static Properties properties(String name, String value) {
        Properties properties = new Properties();
        properties.setProperty(name, value);
        return properties;
    }

    private static SQLException invalid(org.junit.jupiter.api.function.Executable call) {
        SQLNonTransientConnectionException error =
            assertThrows(SQLNonTransientConnectionException.class, call);

        assertEquals("08001", error.getSQLState());
        return error;
    }
}
