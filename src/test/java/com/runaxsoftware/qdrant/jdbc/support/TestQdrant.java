package com.runaxsoftware.qdrant.jdbc.support;

import com.runaxsoftware.qdrant.jdbc.DriverInfo;
import com.runaxsoftware.qdrant.jdbc.connection.ConnectionSettings;
import com.runaxsoftware.qdrant.jdbc.connection.Setting;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;

import java.sql.SQLException;
import java.util.Properties;

/**
 * Where the integration tests and the seed task find Qdrant, by default the three servers in
 * {@code compose.yml}:
 *
 * <ul>
 *   <li>{@code QDRANT_JDBC_URL} (default {@code jdbc:qdrant://localhost:6333}) and, if that
 *       server needs one, {@code QDRANT_API_KEY};</li>
 *   <li>{@code QDRANT_SECURED_JDBC_URL} (default {@code jdbc:qdrant://localhost:6335}) and
 *       {@code QDRANT_SECURED_API_KEY} (default {@code integration-test-key}), a server that
 *       requires an API key, for the authentication tests;</li>
 *   <li>{@code QDRANT_CLUSTER_JDBC_URL} (default {@code jdbc:qdrant://localhost:6337}), a server
 *       running as a cluster of one node, for the cluster procedures.</li>
 * </ul>
 *
 * <p>Both URLs point at Qdrant's REST port, which is what the driver talks to.
 */
public final class TestQdrant {

    private TestQdrant() {
    }

    /**
     * The JDBC URL to connect to.
     */
    public static String jdbcUrl() {
        return env("QDRANT_JDBC_URL", "jdbc:qdrant://localhost:6333");
    }

    /**
     * Connection properties, with the API key when one is set.
     */
    public static Properties properties() {
        Properties properties = new Properties();
        String apiKey = env("QDRANT_API_KEY", "");

        if (!apiKey.isEmpty()) {
            properties.setProperty(Setting.PASSWORD.key(), apiKey);
        }

        return properties;
    }

    /**
     * The JDBC URL of the server that requires an API key.
     */
    public static String securedJdbcUrl() {
        return env("QDRANT_SECURED_JDBC_URL", "jdbc:qdrant://localhost:6335");
    }

    /**
     * The API key of the server that requires one.
     */
    public static String securedApiKey() {
        return env("QDRANT_SECURED_API_KEY", "integration-test-key");
    }

    /**
     * The JDBC URL of the server that runs as a cluster of one node, which is what Qdrant needs
     * for shard keys and the other cluster operations.
     */
    public static String clusterJdbcUrl() {
        return env("QDRANT_CLUSTER_JDBC_URL", "jdbc:qdrant://localhost:6337");
    }

    /**
     * Opens a gateway to the cluster server, for setting up data.
     */
    public static QdrantGateway clusterGateway() throws SQLException {
        ConnectionSettings settings = ConnectionSettings.parse(clusterJdbcUrl(), new Properties());
        return QdrantGateway.open(settings.host(), settings.port(), settings.tls(),
            settings.apiKey(), settings.timeout(), DriverInfo.USER_AGENT);
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
