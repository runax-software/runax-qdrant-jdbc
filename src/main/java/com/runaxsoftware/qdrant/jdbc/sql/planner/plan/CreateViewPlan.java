package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * Create a Qdrant alias, or with OR REPLACE move one to another collection in one atomic
 * change.
 *
 * @param view       the alias
 * @param collection the collection it points at
 * @param orReplace  whether an existing alias moves rather than being an error
 */
public record CreateViewPlan(String view, String collection, boolean orReplace) implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
