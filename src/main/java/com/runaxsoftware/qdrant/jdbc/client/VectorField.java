package com.runaxsoftware.qdrant.jdbc.client;

/**
 * One vector of a collection.
 *
 * @param name     the vector's name, or an empty string for a collection's single unnamed vector
 * @param kind     dense, multivector, or sparse
 * @param size     the number of dimensions, or 0 for sparse vectors
 * @param distance the distance function, such as {@code "Cosine"}, or {@code null} for sparse vectors
 */
public record VectorField(String name, VectorKind kind, int size, String distance) {

    /**
     * Whether this is a collection's single unnamed vector.
     */
    public boolean isUnnamed() {
        return name.isEmpty();
    }
}
