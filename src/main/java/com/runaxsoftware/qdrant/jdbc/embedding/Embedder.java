package com.runaxsoftware.qdrant.jdbc.embedding;

/**
 * Turns text into a vector, for similarity search with {@code embed('...')}
 */
public interface Embedder {
    /**
     * Embeds the text into a dense vector
     */
   float[] embed(String text);
}
