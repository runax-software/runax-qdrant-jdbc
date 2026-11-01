package com.runaxsoftware.qdrant.jdbc.embedding;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;

/**
 * A model that turns text into vectors, and who runs it, written {@code provider:model}:
 * {@code openai:text-embedding-3-small}, {@code qdrant:qdrant/bm25}. The model's own name follows
 * the colon unchanged, slashes and all, so it's whatever the provider calls it. The same text
 * names the model in {@code embed()} and in a collection's metadata, so the two can be compared.
 *
 * @param provider who runs the model
 * @param name     the model's name, as the provider knows it
 */
public record EmbeddingModel(Provider provider, String name) {

    private static final String METADATA_PREFIX = "runax.embed.";

    /**
     * Reads a {@code provider:model} id.
     */
    public static EmbeddingModel parse(String id) throws SQLException {
        int colon = id.indexOf(':');

        if (colon <= 0 || colon == id.length() - 1) {
            throw new SQLSyntaxErrorException("'" + id + "' isn't a model: write who runs it and "
                + "its name, as openai:text-embedding-3-small or qdrant:qdrant/bm25", "42000");
        }

        String prefix = id.substring(0, colon);
        Provider provider = Provider.named(prefix).orElseThrow(() -> new SQLSyntaxErrorException(
            "Unknown embedding provider '" + prefix + "' in '" + id + "': the driver knows "
                + Provider.names(), "42000"));

        return new EmbeddingModel(provider, id.substring(colon + 1).strip());
    }

    /**
     * The collection metadata entry that holds the model a vector was embedded with, so every
     * search of it embeds the same way: {@code runax.embed.dense}, or {@code runax.embed._vector}
     * for a collection's single unnamed vector.
     *
     * @param vector the vector's column name
     */
    public static String metadataKey(String vector) {
        return METADATA_PREFIX + vector;
    }

    /**
     * The model as {@code provider:model}.
     */
    public String id() {
        return provider.prefix() + ":" + name;
    }

    @Override
    public String toString() {
        return id();
    }
}
