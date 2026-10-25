package com.runaxsoftware.qdrant.jdbc;

import com.runaxsoftware.qdrant.jdbc.connection.ConnectionSettings;
import com.runaxsoftware.qdrant.jdbc.connection.QdrantConnection;
import com.runaxsoftware.qdrant.jdbc.connection.QdrantUrl;
import com.runaxsoftware.qdrant.jdbc.connection.Setting;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver for Qdrant.
 *
 * <p>URLs look like {@code jdbc:qdrant://localhost:6333}. The driver talks to Qdrant's REST API,
 * on port 6333 unless the URL says otherwise; {@link QdrantUrl} checks the URL. Settings can go
 * in the URL ({@code ?tls=true&timeout=60}) or in the connection properties, which win when both
 * are given. The API key goes in the {@code password} property only, never in the URL.
 * {@link Setting} lists every setting with its default, and {@link #getPropertyInfo} shows them
 * to tools such as DataGrip.
 *
 * <p>The driver registers itself with {@link DriverManager} when the class loads, and is also
 * listed in {@code META-INF/services/java.sql.Driver}. Tools are configured with this class's
 * name, so it stays in the root package.
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
        if (!acceptsURL(url)) return null;
        ConnectionSettings settings = ConnectionSettings.parse(url, info == null ? new Properties() : info);

        if (settings.logFile() == null) return QdrantConnection.open(url, settings);
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
        if (url == null) throw new SQLException("The URL is null", "08001");
        return QdrantUrl.accepts(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        Properties current = info == null ? new Properties() : info;

        return Arrays.stream(Setting.values())
            .map(setting -> property(setting, current.getProperty(setting.key())))
            .toArray(DriverPropertyInfo[]::new);
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

    private static DriverPropertyInfo property(Setting setting, String value) {
        String shown = setting == Setting.PASSWORD
            ? null
            : value == null ? setting.defaultValue() : value;

        DriverPropertyInfo property = new DriverPropertyInfo(setting.key(), shown);
        property.description = setting.description();
        property.required = false;
        property.choices = setting.choices().toArray(String[]::new);

        return property;
    }
}
