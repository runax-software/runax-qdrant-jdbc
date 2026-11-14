package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * Forget every issue Qdrant has reported about itself, which is what truncating
 * {@code system.issues} asks for. Qdrant reports an issue again when it next comes across what
 * caused it, so this clears a list rather than settling anything.
 */
public record ClearIssuesPlan() implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
