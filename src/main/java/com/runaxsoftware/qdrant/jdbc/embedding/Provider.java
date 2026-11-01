package com.runaxsoftware.qdrant.jdbc.embedding;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Who turns text into a vector: Qdrant itself, or a provider's API the driver calls.
 */
public enum Provider {
    /**
     * Qdrant embeds the text on the server: every Qdrant runs {@code qdrant/bm25}, and Qdrant
     * Cloud runs hosted models.
     */
    QDRANT("qdrant"),

    /**
     * OpenAI's embeddings API, called by the driver.
     */
    OPENAI("openai"),

    /**
     * Voyage AI's embeddings API, called by the driver.
     */
    VOYAGE("voyage");

    private final String prefix;

    Provider(String prefix) {
        this.prefix = prefix;
    }

    /**
     * The name a model id starts with, as {@code openai} in {@code openai:text-embedding-3-small}.
     */
    public String prefix() {
        return prefix;
    }

    /**
     * Whether Qdrant embeds the text rather than the driver.
     */
    public boolean embedsOnServer() {
        return this == QDRANT;
    }

    static Optional<Provider> named(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);

        return Arrays.stream(values()).filter(x -> x.prefix.equals(lower)).findFirst();
    }

    static String names() {
        return Arrays.stream(values()).map(Provider::prefix).collect(Collectors.joining(", "));
    }
}
