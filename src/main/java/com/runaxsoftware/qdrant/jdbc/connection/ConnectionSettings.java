package com.runaxsoftware.qdrant.jdbc.connection;

import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

/**
 * What the driver needs to open a connection, read from the URL and the connection
 * properties: where Qdrant is, the API key, and the driver's own settings.
 *
 * @param host        the server's host name or address
 * @param port        the server's REST port
 * @param tls         whether to connect over TLS
 * @param apiKey      the API key, or {@code null} for none
 * @param timeout     how long to wait for each call to Qdrant
 * @param sampleSize  how many points to read when working out a collection's columns
 * @param schemaCache how long to keep a collection's columns before reading them again;
 *                    zero reads them every time
 * @param logFile     the file to log JDBC calls to, or {@code null} for no log
 * @param embedding   the embedding providers' keys and URLs, for {@code embed()}
 */
public record ConnectionSettings(
    String host,
    int port,
    boolean tls,
    String apiKey,
    Duration timeout,
    int sampleSize,
    Duration schemaCache,
    Path logFile,
    Embedding embedding
) {

    /**
     * Where the driver reaches the embedding providers it calls itself.
     *
     * @param openAiKey the OpenAI API key, or {@code null}
     * @param openAiUrl OpenAI's API base URL
     * @param voyageKey the Voyage AI API key, or {@code null}
     * @param voyageUrl Voyage AI's API base URL
     */
    public record Embedding(String openAiKey, URI openAiUrl, String voyageKey, URI voyageUrl) {

        @Override
        public String toString() {
            return "openai=" + (openAiKey == null ? "none" : "set") + ", voyage="
                + (voyageKey == null ? "none" : "set");
        }
    }

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
            Duration.ofSeconds(values.wholeNumber(Setting.SCHEMA_CACHE, 0, Integer.MAX_VALUE)),
            values.path(Setting.LOG_FILE),
            new Embedding(values.secret(Setting.OPENAI_API_KEY), values.uri(Setting.OPENAI_URL),
                values.secret(Setting.VOYAGE_API_KEY), values.uri(Setting.VOYAGE_URL))
        );
    }

    /**
     * The settings for logs and error messages, saying only whether an API key is set.
     */
    @Override
    public String toString() {
        return "ConnectionSettings[host=" + host + ", port=" + port + ", tls=" + tls
            + ", apiKey=" + (apiKey == null ? "none" : "set") + ", timeout=" + timeout.toSeconds() + "s"
            + ", sampleSize=" + sampleSize + ", schemaCache=" + schemaCache.toSeconds() + "s"
            + ", logFile=" + logFile + ", " + embedding + "]";
    }
}
