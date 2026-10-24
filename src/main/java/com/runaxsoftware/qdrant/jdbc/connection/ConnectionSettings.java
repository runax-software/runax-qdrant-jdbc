package com.runaxsoftware.qdrant.jdbc.connection;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

/**
 * What the driver needs to open a connection, read from the URL and the connection
 * properties: where Qdrant is, the API key, and the driver's own settings.
 *
 * @param host       the server's host name or address
 * @param port       the server's gRPC port
 * @param tls        whether to connect over TLS
 * @param apiKey     the API key, or {@code null} for none
 * @param timeout    how long to wait for each call to Qdrant
 * @param sampleSize how many points to read when working out a collection's columns
 * @param logFile    the file to log JDBC calls to, or {@code null} for no log
 */
public record ConnectionSettings(
    String host,
    int port,
    boolean tls,
    String apiKey,
    Duration timeout,
    int sampleSize,
    Path logFile
) {
    public static ConnectionSettings parse(String url, Properties properties) throws SQLException {
        QdrantUrl qdrantUrl = QdrantUrl.parse(url);
        SettingValues values = SettingValues.merge(qdrantUrl.parameters(), properties);

        return new ConnectionSettings(
            qdrantUrl.host(),
            qdrantUrl.port(),
            values.bool(Setting.TLS),
            values.secret(Setting.PASSWORD),
            Duration.ofSeconds(values.wholeNumber(Setting.TIMEOUT, 1, Integer.MAX_VALUE)),
            values.wholeNumber(Setting.SAMPLE_SIZE, 0, 10_000),
            values.path(Setting.LOG_FILE)
        );
    }
}
