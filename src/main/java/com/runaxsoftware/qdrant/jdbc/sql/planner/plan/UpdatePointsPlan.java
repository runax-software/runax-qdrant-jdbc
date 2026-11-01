package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Change the payload of the points a filter matches, through one call to Qdrant's batch update
 * API.
 *
 * @param collection the collection whose points change
 * @param filter     the points to change, as a Qdrant filter
 * @param operations the batch's operations, in order, each holding the filter
 */
public record UpdatePointsPlan(String collection, JsonNode filter, List<JsonNode> operations)
    implements Plan {

    public UpdatePointsPlan {
        operations = List.copyOf(operations);
    }

    @Override
    public boolean returnsRows() {
        return false;
    }
}
