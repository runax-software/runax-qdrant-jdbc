package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

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
 */
public record ScrollPlan(
    String collection,
    List<ColumnDefinition> columns,
    boolean withPayload,
    boolean withVector,
    Long limit
) implements Plan {}
