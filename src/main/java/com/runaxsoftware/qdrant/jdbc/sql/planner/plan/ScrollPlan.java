package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;

import java.util.List;

/**
 * Read points with Qdrant's scroll API, page by page.
 *
 * @param collection  the collection to read
 * @param columns     the result set's columns, in SELECT order
 * @param withPayload whether any column needs the payload
 * @param withVector  whether any column needs a vector
 * @param limit       the maximum number of rows, or {@code null} for no limit
 * @param filter      the statement's WHERE as a Qdrant filter, or {@code null} when it has none
 */
public record ScrollPlan(
    String collection,
    List<ColumnDefinition> columns,
    boolean withPayload,
    boolean withVector,
    Long limit,
    JsonNode filter
) implements Plan {

    /**
     * A read of every point, unfiltered.
     */
    public ScrollPlan(String collection, List<ColumnDefinition> columns, boolean withPayload,
                      boolean withVector, Long limit) {
        this(collection, columns, withPayload, withVector, limit, null);
    }
}
