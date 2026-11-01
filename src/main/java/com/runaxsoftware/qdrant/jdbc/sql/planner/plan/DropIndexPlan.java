package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import java.util.List;

/**
 * Delete the payload index on a field. The statement may name the index as the driver reports
 * it, {@code lang_idx}, or by its field, so the plan holds every field the name could mean, and
 * the index Qdrant has decides.
 *
 * @param collection the collection
 * @param fields     the fields the statement could mean, the likeliest first
 * @param written    the index or field as the statement wrote it, for errors
 * @param ifExists   whether a field without an index is fine
 */
public record DropIndexPlan(String collection, List<String> fields, String written,
                            boolean ifExists) implements Plan {

    public DropIndexPlan {
        fields = List.copyOf(fields);
    }

    @Override
    public boolean returnsRows() {
        return false;
    }
}
