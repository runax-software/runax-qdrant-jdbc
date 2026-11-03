package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Add points to a collection, in one upsert, and do what the statement says with any point
 * that's already there.
 *
 * @param collection   the collection the points go into
 * @param points       the points, one per row of VALUES, in order
 * @param onConflict   what to do with a point the collection already has
 * @param updateFilter the condition an existing point must meet to be replaced, as a Qdrant
 *                     filter, or {@code null} for all of them. Qdrant applies it to the points
 *                     already there and inserts new ones whatever it says, which is what
 *                     {@code DO UPDATE ... WHERE} means in SQL
 */
public record InsertPointsPlan(String collection, List<NewPoint> points,
                               ConflictAction onConflict, JsonNode updateFilter) implements Plan {

    public InsertPointsPlan {
        points = List.copyOf(points);
    }

    /**
     * An insert where a point that's already there is an error.
     */
    public InsertPointsPlan(String collection, List<NewPoint> points) {
        this(collection, points, ConflictAction.FAIL, null);
    }

    @Override
    public boolean returnsRows() {
        return false;
    }
}
