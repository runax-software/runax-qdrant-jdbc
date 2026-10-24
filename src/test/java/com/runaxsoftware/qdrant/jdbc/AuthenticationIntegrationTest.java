package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.client.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Connects to a Qdrant that requires an API key: the {@code qdrant-secured} server in
 * {@code compose.yml}. Needs {@code docker compose up -d}.
 */
@Tag("integration")
class AuthenticationIntegrationTest {

    @Test
    void connectsWithTheApiKeyInThePasswordField() throws SQLException {
        try (Connection connection = connect(TestQdrant.securedApiKey())) {
            assertTrue(connection.isValid(5));
            connection.getMetaData().getTables(null, null, "%", null).close();
        }
    }

    @Test
    void connectsThroughDriverManagerWithAnEmptyUser() throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestQdrant.securedJdbcUrl(), "",
            TestQdrant.securedApiKey())) {
            assertTrue(connection.isValid(5));
        }
    }

    @Test
    void rejectsAWrongApiKeyWithoutRepeatingIt() {
        SQLInvalidAuthorizationSpecException error = assertThrows(
            SQLInvalidAuthorizationSpecException.class, () -> connect("wrong-key-1234"));

        assertEquals("28000", error.getSQLState());
        assertTrue(error.getMessage().contains("password field"), error.getMessage());
        assertFalse(error.getMessage().contains("wrong-key-1234"));
    }

    @Test
    void rejectsAMissingApiKey() {
        SQLInvalidAuthorizationSpecException error = assertThrows(
            SQLInvalidAuthorizationSpecException.class, () -> connect(null));

        assertTrue(error.getMessage().contains("API key"), error.getMessage());
    }

    @Test
    void readsMetadataWithTheApiKey() throws Exception {
        String prefix = "jdbc_auth_" + UUID.randomUUID().toString().substring(0, 8) + "_";

        try (QdrantGateway gateway = QdrantGateway.open("localhost", securedPort(), false,
            TestQdrant.securedApiKey(), Duration.ofSeconds(30), DriverInfo.USER_AGENT)) {
            SampleCollections.create(gateway, prefix);

            try (Connection connection = connect(TestQdrant.securedApiKey());
                 ResultSet columns = connection.getMetaData().getColumns(null, null,
                     prefix.replace("_", "\\_") + "products", "%")) {
                List<String> names = new ArrayList<>();

                while (columns.next()) {
                    names.add(columns.getString("COLUMN_NAME"));
                }

                assertEquals(List.of("id", "created_at", "in_stock", "name", "price", "specs",
                    "specs.color", "tags", "payload", "vector"), names);
            } finally {
                SampleCollections.delete(gateway, prefix);
            }
        }
    }

    @Test
    void keepsTheApiKeyOutOfTheCallLog(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("calls.log");
        Properties properties = new Properties();
        properties.setProperty("password", TestQdrant.securedApiKey());
        properties.setProperty("logFile", log.toString());

        try (Connection connection = DriverManager.getConnection(TestQdrant.securedJdbcUrl(),
            properties)) {
            connection.getMetaData().getTables(null, null, "%", null).close();
        }

        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(text.contains("DatabaseMetaData.getTables"), text);
        assertFalse(text.contains(TestQdrant.securedApiKey()));
    }

    private static Connection connect(String apiKey) throws SQLException {
        Properties properties = new Properties();

        if (apiKey != null) {
            properties.setProperty("password", apiKey);
        }

        return DriverManager.getConnection(TestQdrant.securedJdbcUrl(), properties);
    }

    private static int securedPort() throws SQLException {
        return ConnectionSettings.parse(TestQdrant.securedJdbcUrl(), new Properties()).port();
    }
}
