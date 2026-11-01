package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Count the points per value of one payload field, with Qdrant's facet API, which is what a
 * GROUP BY with COUNT(*) asks for. Qdrant answers the most common values first and returns no
 * cursor, so one call answers the whole statement and its limit says how many groups to read.
 *
 * @param collection the collection to group
 * @param key        the payload field path the points are grouped by
 * @param columns    the result set's columns, in SELECT order
 * @param filter     the Qdrant filter the counted points must match, or {@code null} for all
 *                   of them
 * @param limit      the maximum number of groups, or {@code null} when the statement gave none
 *                   and the statement's maximum rows decide
 */
public record FacetPlan(
    String collection,
    String key,
    List<FacetColumn> columns,
    JsonNode filter,
    Long limit
) implements Plan {

    public FacetPlan(String collection, String key, List<FacetColumn> columns, JsonNode filter,
                     Long limit) {
        this.collection = collection;
        this.key = key;
        this.columns = List.copyOf(columns);
        this.filter = filter;
        this.limit = limit;
    }
}
