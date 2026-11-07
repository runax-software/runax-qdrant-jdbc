package com.runaxsoftware.qdrant.jdbc.embedding;

/**
 * What a text is for. Some models embed a search query and a stored document differently, so
 * that a short question lands near the passages that answer it; others ignore the role.
 */
public enum TextRole {
    /**
     * A search query: {@code embed('...')} inside {@code similarity()} and the other ranking
     * functions.
     */
    QUERY,

    /**
     * A document to store: {@code embed('...')} inside {@code INSERT} and {@code UPDATE}.
     */
    DOCUMENT
}
