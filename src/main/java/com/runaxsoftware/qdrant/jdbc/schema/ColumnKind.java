package com.runaxsoftware.qdrant.jdbc.schema;

/**
 * Where a column's values come from.
 */
public enum ColumnKind {
    /**
     * The point ID.
     */
    ID,

    /**
     * One payload field, top-level or nested.
     */
    PAYLOAD_FIELD,

    /**
     * The whole payload, as JSON.
     */
    PAYLOAD,

    /**
     * One of the collection's vectors.
     */
    VECTOR,

    /**
     * How close a search scored the point. A collection has no such column of its own: only a
     * search has a score, so only a search's select list may name {@code _score}.
     */
    SCORE
}
