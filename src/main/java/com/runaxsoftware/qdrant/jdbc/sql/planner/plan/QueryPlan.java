package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;

import java.util.List;

/**
 * Rank a collection's points by how close they are to a vector, through Qdrant's Query API.
 * One call answers the whole search, so unlike a scroll it reads no cursor; the limit says how
 * many points Qdrant ranks.
 *
 * @param collection  the collection to search
 * @param columns     the result set's columns, in SELECT order, which may include the score
 * @param vector      the name of the vector to search, empty for a collection's single unnamed
 *                    vector
 * @param query       the vector to search with, of that vector's dimensions
 * @param withPayload whether any column needs the payload
 * @param withVector  whether any column needs a vector
 * @param limit       the maximum number of rows, or {@code null} when the statement gave none
 *                    and the statement's maximum rows decide
 */
public record QueryPlan(
    String collection,
    List<ColumnDefinition> columns,
    String vector,
    List<Double> query,
    boolean withPayload,
    boolean withVector,
    Long limit
) implements Plan {

    public QueryPlan(String collection, List<ColumnDefinition> columns, String vector,
                     List<Double> query, boolean withPayload, boolean withVector, Long limit) {
        this.collection = collection;
        this.columns = List.copyOf(columns);
        this.vector = vector;
        this.query = List.copyOf(query);
        this.withPayload = withPayload;
        this.withVector = withVector;
        this.limit = limit;
    }
}
