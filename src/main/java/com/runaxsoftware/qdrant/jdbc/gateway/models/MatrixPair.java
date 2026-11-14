package com.runaxsoftware.qdrant.jdbc.gateway.models;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One cell of a distance matrix: a sampled point, one of its nearest neighbours, and how close
 * the two are.
 *
 * @param a     the point's ID
 * @param b     the neighbour's ID
 * @param score how close they are, by the vector's distance
 * @param row   the point's position among the sampled points, or {@code null} when the matrix
 *              was asked for as pairs
 * @param col   the neighbour's position among them, or {@code null} likewise
 */
public record MatrixPair(JsonNode a, JsonNode b, double score, Long row, Long col) {
}
