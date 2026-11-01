package com.runaxsoftware.qdrant.jdbc.embedding;

import java.sql.SQLException;

/**
 * The embedders a connection can use, one per provider that the driver calls itself.
 */
@FunctionalInterface
public interface Embedders {

    /**
     * The embedder for a provider; fails when the connection isn't set up for it, such as one
     * without the provider's API key.
     */
    Embedder embedder(Provider provider) throws SQLException;

    /**
     * Embedders for none of the providers, for executors that run no driver-side embedding.
     */
    static Embedders none() {
        return provider -> {
            throw new SQLException("Embedding with " + provider.prefix() + " isn't set up for "
                + "this connection", "0A000");
        };
    }
}
