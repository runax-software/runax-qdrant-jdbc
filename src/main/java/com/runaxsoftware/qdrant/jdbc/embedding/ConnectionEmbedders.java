package com.runaxsoftware.qdrant.jdbc.embedding;

import com.runaxsoftware.qdrant.jdbc.connection.ConnectionSettings;

import java.net.http.HttpClient;
import java.sql.SQLException;
import java.time.Duration;

/**
 * The embedders a connection's settings set up: a provider is usable once its API key is
 * given. The embedders share one HTTP client and the connection's timeout.
 */
public final class ConnectionEmbedders implements Embedders {

    private final ConnectionSettings.Embedding settings;
    private final Duration timeout;
    private final HttpClient http;

    public ConnectionEmbedders(ConnectionSettings.Embedding settings, Duration timeout) {
        this.settings = settings;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Embedder embedder(Provider provider) throws SQLException {
        return switch (provider) {
            case OPENAI -> new OpenAiEmbedder(http, settings.openAiUrl(),
                key(settings.openAiKey(), "openaiApiKey", "OpenAI"), timeout);
            case VOYAGE -> new VoyageAiEmbedder(http, settings.voyageUrl(),
                key(settings.voyageKey(), "voyageApiKey", "Voyage AI"), timeout);
            case QDRANT -> throw new IllegalArgumentException("Qdrant embeds on the server");
        };
    }

    private static String key(String key, String setting, String provider) throws SQLException {
        if (key == null) {
            throw new SQLException("Embedding with " + provider + " needs its API key: set the "
                + setting + " connection property", "28000");
        }

        return key;
    }
}
