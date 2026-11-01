package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Create a collection, then its payload indexes.
 *
 * @param collection  the collection to create
 * @param ifNotExists whether an existing collection is fine rather than an error
 * @param config      the collection's vectors, as Qdrant's create-collection API takes them
 * @param indexes     the payload indexes to create once the collection is there, in order
 */
public record CreateCollectionPlan(String collection, boolean ifNotExists, JsonNode config,
                                   List<PayloadIndex> indexes) implements Plan {

    /**
     * One payload index.
     *
     * @param field the payload field, which may be a nested path such as {@code specs.color}
     * @param type  Qdrant's name for the index type, such as {@code keyword}
     */
    public record PayloadIndex(String field, String type) {
    }

    public CreateCollectionPlan {
        indexes = List.copyOf(indexes);
    }

    @Override
    public boolean returnsRows() {
        return false;
    }
}
