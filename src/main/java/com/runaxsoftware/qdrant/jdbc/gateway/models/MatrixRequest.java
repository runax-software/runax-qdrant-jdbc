package com.runaxsoftware.qdrant.jdbc.gateway.models;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A request for a distance matrix: Qdrant samples points of a collection and finds each one's
 * nearest neighbours among the sample.
 *
 * @param collection the collection to sample
 * @param vector     the name of the vector to compare, empty for the unnamed one
 * @param sample     how many points to sample, or {@code null} for Qdrant's own number, 10
 * @param neighbours how many neighbours to find for each, or {@code null} for Qdrant's, 3
 * @param filter     the points to sample from, as a Qdrant filter, or {@code null} for all
 * @param offsets    whether to ask for the matrix as row and column positions, which is how a
 *                   sparse matrix is built, rather than as pairs of point IDs alone
 */
public record MatrixRequest(String collection, String vector, Long sample, Long neighbours,
                            JsonNode filter, boolean offsets) {
}
