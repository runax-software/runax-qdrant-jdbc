package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * Delete a named vector from a collection, and every point's value for it.
 *
 * @param collection the collection to change
 * @param vector     the vector's name
 * @param ifExists   whether a missing vector is fine rather than an error
 */
public record DropVectorPlan(String collection, String vector, boolean ifExists)
    implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
