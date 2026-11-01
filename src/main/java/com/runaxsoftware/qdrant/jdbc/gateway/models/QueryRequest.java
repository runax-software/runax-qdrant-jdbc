package com.runaxsoftware.qdrant.jdbc.gateway.models;

import com.fasterxml.jackson.databind.JsonNode;

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
 * @param filter      the points to rank, as a Qdrant filter, or {@code null} for every point
 * @param document    text for Qdrant to embed and search with instead of the query vector, or
 *                    {@code null}
 */
public record QueryRequest(
    String collection,
    String vector,
    List<Double> query,
    long limit,
    boolean withPayload,
    boolean withVector,
    JsonNode filter,
    Document document) {

    public QueryRequest(String collection, String vector, List<Double> query, long limit,
                        boolean withPayload, boolean withVector, JsonNode filter,
                        Document document) {
        this.collection = collection;
        this.vector = vector;
        this.query = List.copyOf(query);
        this.limit = limit;
        this.withPayload = withPayload;
        this.withVector = withVector;
        this.filter = filter;
        this.document = document;
    }

    /**
     * A search with a vector written out.
     */
    public QueryRequest(String collection, String vector, List<Double> query, long limit,
                        boolean withPayload, boolean withVector, JsonNode filter) {
        this(collection, vector, query, limit, withPayload, withVector, filter, null);
    }

    /**
     * A search of every point, unfiltered.
     */
    public QueryRequest(String collection, String vector, List<Double> query, long limit,
                        boolean withPayload, boolean withVector) {
        this(collection, vector, query, limit, withPayload, withVector, null);
    }
}
