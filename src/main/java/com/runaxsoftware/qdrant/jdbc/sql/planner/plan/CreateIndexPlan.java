package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Create a payload index on one field, unless the field has one, which is an error or, with IF
 * NOT EXISTS, nothing to do.
 *
 * @param collection  the collection
 * @param field       the payload field
 * @param schema      the index as Qdrant's create-index API takes it: a type, or an object of a
 *                    type and its options
 * @param ifNotExists whether a field that has an index already is fine
 */
public record CreateIndexPlan(String collection, String field, JsonNode schema,
                              boolean ifNotExists) implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
