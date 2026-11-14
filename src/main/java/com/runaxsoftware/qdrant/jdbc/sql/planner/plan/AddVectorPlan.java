package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Add a named vector to a collection.
 *
 * @param collection  the collection to change
 * @param vector      the new vector's name
 * @param config      the vector, as Qdrant's create-vector API takes it: under {@code dense} or
 *                    {@code sparse}
 * @param ifNotExists whether a vector already there under the name is fine rather than an error
 */
public record AddVectorPlan(String collection, String vector, JsonNode config,
                            boolean ifNotExists) implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
