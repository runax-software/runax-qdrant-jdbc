package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import java.util.List;

/**
 * Add new points to a collection, in one upsert that skips any point already there.
 *
 * @param collection the collection the points go into
 * @param points     the points, one per row of VALUES, in order
 */
public record InsertPointsPlan(String collection, List<NewPoint> points) implements Plan {

    public InsertPointsPlan {
        points = List.copyOf(points);
    }

    @Override
    public boolean returnsRows() {
        return false;
    }
}
