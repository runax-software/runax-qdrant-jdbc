package com.runaxsoftware.qdrant.jdbc.embedding;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.Locale;

/**
 * A model that turns text into vectors, and who runs it, written
 * {@code provider:model[?dimensions=n]}: {@code openai:text-embedding-3-small},
 * {@code qdrant:qdrant/bm25}, {@code openai:text-embedding-3-large?dimensions=1024}. The model's
 * own name follows the colon unchanged, slashes and all, so it's whatever the provider calls it.
 * The same text names the model in {@code embed()} and in a collection's metadata, so the two can
 * be compared.
 *
 * <p>Some models can return fewer dimensions than they were trained for, which the provider does
 * better than truncating the vector afterwards. Asking for a size is part of the ID rather than a
 * connection setting, so a collection's metadata names one vector exactly: every client that
 * reads it embeds the same way, and a search can't quietly ask for a different size from the one
 * the points were stored with.
 *
 * @param provider   who runs the model
 * @param name       the model's name, as the provider knows it
 * @param dimensions the size to ask the model for, or 0 for the model's own size
 */
public record EmbeddingModel(Provider provider, String name, int dimensions) {

    private static final String METADATA_PREFIX = "runax.embed.";
    private static final String DIMENSIONS = "?dimensions=";

    /**
     * Checks the size is one a provider could return.
     */
    public EmbeddingModel {
        if (dimensions < 0) {
            throw new IllegalArgumentException("Dimensions can't be negative: " + dimensions);
        }
    }

    /**
     * A model at its own size.
     */
    public EmbeddingModel(Provider provider, String name) {
        this(provider, name, 0);
    }

    /**
     * Reads a {@code provider:model[?dimensions=n]} id.
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

        String rest = id.substring(colon + 1).strip();
        int question = rest.indexOf('?');

        if (question < 0) {
            return new EmbeddingModel(provider, rest);
        }

        return new EmbeddingModel(provider, rest.substring(0, question).strip(),
            dimensions(rest.substring(question), id));
    }

    /**
     * The size a {@code ?dimensions=n} suffix asks for. A suffix the driver doesn't know is an
     * error rather than part of the model's name: a provider would reject that name anyway, and
     * silently searching with a model whose ID was misread finds the wrong points.
     */
    private static int dimensions(String suffix, String id) throws SQLException {
        String usage = "write it as openai:text-embedding-3-large?dimensions=1024, or leave it "
            + "out for the model's own size";

        if (!suffix.toLowerCase(Locale.ROOT).startsWith(DIMENSIONS)) {
            throw new SQLSyntaxErrorException("'" + id + "' isn't a model: the only thing that "
                + "follows a model's name is its size, and '" + suffix + "' isn't that — " + usage,
                "42000");
        }

        String value = suffix.substring(DIMENSIONS.length()).strip();
        int dimensions;

        try {
            dimensions = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new SQLSyntaxErrorException("'" + value + "' isn't a number of dimensions in '"
                + id + "': " + usage, "42000", e);
        }

        if (dimensions <= 0) {
            throw new SQLSyntaxErrorException("A model can't be asked for " + dimensions
                + " dimensions in '" + id + "': " + usage, "42000");
        }

        return dimensions;
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
     * The model as {@code provider:model[?dimensions=n]}, which {@link #parse} reads back.
     */
    public String id() {
        return provider.prefix() + ":" + name + (dimensions > 0 ? DIMENSIONS + dimensions : "");
    }

    @Override
    public String toString() {
        return id();
    }
}
