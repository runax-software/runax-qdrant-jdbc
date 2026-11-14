package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;

import java.util.List;

/**
 * Read the one point with an ID, through Qdrant's get-point API: what a SELECT whose whole
 * WHERE is {@code _id = ...} asks for. It answers with the point or with none, and needs no
 * filter and no page.
 *
 * @param collection the collection to read
 * @param id         the point's ID, a whole number or a UUID, as Qdrant takes it
 * @param columns    the result set's columns, in SELECT order
 * @param limit      the maximum number of rows, or {@code null} when the statement gave none;
 *                   0 means the point isn't read at all
 * @param offset     the rows to pass over; any at all passes over the one there is
 */
public record PointPlan(String collection, JsonNode id, List<ColumnDefinition> columns,
                        Long limit, long offset) implements Plan {

    public PointPlan {
        columns = List.copyOf(columns);
    }
}
