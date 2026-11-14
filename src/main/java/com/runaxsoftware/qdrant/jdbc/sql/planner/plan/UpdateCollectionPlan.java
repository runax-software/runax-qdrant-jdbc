package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Change a collection's settings, or those of its vectors, in place.
 *
 * @param collection the collection to change
 * @param changes    the settings to change, as Qdrant's update-collection API takes them;
 *                   whatever isn't named stays as it is
 */
public record UpdateCollectionPlan(String collection, JsonNode changes) implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
