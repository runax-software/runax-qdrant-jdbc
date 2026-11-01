package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * Delete an alias; the collection it points at stays.
 *
 * @param view     the alias
 * @param ifExists whether a missing alias is fine
 */
public record DropViewPlan(String view, boolean ifExists) implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
