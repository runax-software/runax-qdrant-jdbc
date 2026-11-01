package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Count a collection's points with Qdrant's count API, which answers one number, so this plan
 * has one row of one column. Counting needs none of the collection's columns, so a count reads
 * no payloads, no vectors, and not even the collection's schema unless a WHERE condition names
 * one of its columns: an unknown collection is what Qdrant reports when the count runs.
 *
 * @param collection the collection to count
 * @param column     the name of the single column: the name the statement gave the count, or
 *                   the one the driver gives it
 * @param filter     the Qdrant filter the counted points must match, or {@code null} to count
 *                   the whole collection
 * @param limit      the maximum number of rows, or {@code null} when the statement gave none.
 *                   A count is one row, so only a limit of none leaves it out
 * @param offset     the rows an OFFSET passes over, or 0 for none. A count is one row, so any
 *                   OFFSET at all skips past it and nothing is counted
 */
public record CountPlan(String collection, String column, JsonNode filter, Long limit,
                        long offset) implements Plan {

    /**
     * A count of the first row, which is every count without an OFFSET.
     */
    public CountPlan(String collection, String column, JsonNode filter, Long limit) {
        this(collection, column, filter, limit, 0);
    }
}
