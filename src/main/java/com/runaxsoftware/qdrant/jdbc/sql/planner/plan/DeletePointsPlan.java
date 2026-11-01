package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Delete the points a filter matches.
 *
 * @param collection the collection to delete points from
 * @param filter     the points to delete, as a Qdrant filter; an empty filter matches every
 *                   point, which only TRUNCATE asks for
 */
public record DeletePointsPlan(String collection, JsonNode filter) implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
