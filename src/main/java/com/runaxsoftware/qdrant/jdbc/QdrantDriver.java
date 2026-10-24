package com.runaxsoftware.qdrant.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver for Qdrant.
 *
 * <p>URLs look like {@code jdbc:qdrant://localhost:6334}. The driver talks to Qdrant over gRPC,
 * through the official Qdrant Java SDK; port 6334 is the default. Settings can go in the URL ({@code ?tls=true&timeout=60}) or
 * in the connection properties; the API key goes in the {@code password} property only. See
 * {@link ConnectionSettings} for every setting.
 *
 * <p>The driver registers itself with {@link DriverManager} when the class loads, and is also
 * listed in {@code META-INF/services/java.sql.Driver}.
 */
public final class QdrantDriver implements Driver {

    static {
        try {
            DriverManager.registerDriver(new QdrantDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Creates the driver. Applications normally go through {@link DriverManager} instead.
     */
    public QdrantDriver() {
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        ConnectionSettings settings =
            ConnectionSettings.parse(url, info == null ? new Properties() : info);

        if (settings.logFile() == null) {
            return QdrantConnection.open(url, settings);
        }

        CallLog log = CallLog.open(settings.logFile());

        try {
            log.write("connect " + url + " " + settings);
            return log.wrap(QdrantConnection.open(url, settings));
        } catch (SQLException | RuntimeException e) {
            log.write("connect failed: " + e);
            log.close();
            throw e;
        }
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        if (url == null) {
            throw new SQLException("The URL is null", "08001");
        }

        return ConnectionSettings.accepts(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        Properties current = info == null ? new Properties() : info;

        return new DriverPropertyInfo[]{
            property(ConnectionSettings.PASSWORD, null,
                "Qdrant API key. Leave empty if the server doesn't need one."),
            property(ConnectionSettings.TLS, current.getProperty(ConnectionSettings.TLS, "false"),
                "Connect over TLS: true or false.", "false", "true"),
            property(ConnectionSettings.TIMEOUT,
                current.getProperty(ConnectionSettings.TIMEOUT,
                    String.valueOf(ConnectionSettings.DEFAULT_TIMEOUT_SECONDS)),
                "Seconds to wait for Qdrant to connect and to answer each request."),
            property(ConnectionSettings.SAMPLE_SIZE,
                current.getProperty(ConnectionSettings.SAMPLE_SIZE,
                    String.valueOf(ConnectionSettings.DEFAULT_SAMPLE_SIZE)),
                "How many points to read when working out a collection's columns."),
            property(ConnectionSettings.LOG_FILE,
                current.getProperty(ConnectionSettings.LOG_FILE),
                "File to log every JDBC call to, for troubleshooting. "
                    + "The API key is never logged."),
        };
    }

    @Override
    public int getMajorVersion() {
        return DriverInfo.MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return DriverInfo.MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("com.runaxsoftware.qdrant.jdbc");
    }

    private static DriverPropertyInfo property(String name, String value, String description,
                                               String... choices) {
        DriverPropertyInfo property = new DriverPropertyInfo(name, value);

        property.description = description;
        property.required = false;
        property.choices = choices.length == 0 ? null : choices;

        return property;
    }
}
