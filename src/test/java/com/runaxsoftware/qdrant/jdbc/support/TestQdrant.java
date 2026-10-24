package com.runaxsoftware.qdrant.jdbc.support;

import com.runaxsoftware.qdrant.jdbc.ConnectionSettings;
import com.runaxsoftware.qdrant.jdbc.DriverInfo;
import com.runaxsoftware.qdrant.jdbc.client.QdrantGateway;

import java.sql.SQLException;
import java.util.Properties;

/**
 * Where the integration tests and the seed task find Qdrant, by default the two servers in
 * {@code compose.yml}:
 *
 * <ul>
 *   <li>{@code QDRANT_JDBC_URL} (default {@code jdbc:qdrant://localhost:6334}) and, if that
 *       server needs one, {@code QDRANT_API_KEY};</li>
 *   <li>{@code QDRANT_SECURED_JDBC_URL} (default {@code jdbc:qdrant://localhost:6336}) and
 *       {@code QDRANT_SECURED_API_KEY} (default {@code integration-test-key}), a server that
 *       requires an API key, for the authentication tests.</li>
 * </ul>
 */
public final class TestQdrant {

    private TestQdrant() {
    }

    /**
     * The JDBC URL to connect to.
     */
    public static String jdbcUrl() {
        return env("QDRANT_JDBC_URL", "jdbc:qdrant://localhost:6334");
    }

    /**
     * Connection properties, with the API key when one is set.
     */
    public static Properties properties() {
        Properties properties = new Properties();
        String apiKey = env("QDRANT_API_KEY", "");

        if (!apiKey.isEmpty()) {
            properties.setProperty(ConnectionSettings.PASSWORD, apiKey);
        }

        return properties;
    }

    /**
     * The JDBC URL of the server that requires an API key.
     */
    public static String securedJdbcUrl() {
        return env("QDRANT_SECURED_JDBC_URL", "jdbc:qdrant://localhost:6336");
    }

    /**
     * The API key of the server that requires one.
     */
    public static String securedApiKey() {
        return env("QDRANT_SECURED_API_KEY", "integration-test-key");
    }

    /**
     * Opens a gateway to the main server, for setting up data.
     */
    public static QdrantGateway gateway() throws SQLException {
        ConnectionSettings settings = ConnectionSettings.parse(jdbcUrl(), properties());
        return QdrantGateway.open(settings.host(), settings.port(), settings.tls(),
            settings.apiKey(), settings.timeout(), DriverInfo.USER_AGENT);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
