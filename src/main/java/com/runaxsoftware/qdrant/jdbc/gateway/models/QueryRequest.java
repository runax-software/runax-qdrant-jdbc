package com.runaxsoftware.qdrant.jdbc.gateway.models;

import java.util.List;

/**
 * One request for Qdrant's Query API: the points nearest a vector, best first.
 *
 * @param collection  the collection to search
 * @param vector      the name of the vector to search, or an empty string for a collection's
 *                    single unnamed vector
 * @param query       the vector to search with, of the searched vector's dimensions
 * @param limit       how many points to return, which a search always says
 * @param withPayload whether to return payloads
 * @param withVector  whether to return vectors
 */
public record QueryRequest(
    String collection,
    String vector,
    List<Double> query,
    long limit,
    boolean withPayload,
    boolean withVector) {

    public QueryRequest(String collection, String vector, List<Double> query, long limit,
                        boolean withPayload, boolean withVector) {
        this.collection = collection;
        this.vector = vector;
        this.query = List.copyOf(query);
        this.limit = limit;
        this.withPayload = withPayload;
        this.withVector = withVector;
    }
}
