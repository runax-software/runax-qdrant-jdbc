package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * Give an alias a new name.
 *
 * @param view    the alias
 * @param newName its new name
 */
public record RenameViewPlan(String view, String newName) implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
