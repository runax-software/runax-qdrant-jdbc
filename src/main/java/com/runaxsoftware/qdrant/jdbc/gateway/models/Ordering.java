package com.runaxsoftware.qdrant.jdbc.gateway.models;

/**
 * The order a scroll reads points in, which Qdrant runs on a payload field with a range index.
 * A scroll in Qdrant's own order, by point ID, needs none of this.
 *
 * @param key        the payload field path to order by
 * @param descending whether to read from the highest value down
 */
public record Ordering(String key, boolean descending) {

    /**
     * The direction as Qdrant names it.
     */
    public String direction() {
        return descending ? "desc" : "asc";
    }
}
