package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * Delete a collection, with its points and indexes.
 *
 * @param collection the collection to delete
 * @param ifExists   whether a missing collection is fine rather than an error
 */
public record DropCollectionPlan(String collection, boolean ifExists) implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
