package com.runaxsoftware.qdrant.jdbc.gateway.models;

/**
 * The kinds of vectors a Qdrant collection can store.
 */
public enum VectorKind {
    /**
     * One fixed-size vector of floats per point.
     */
    DENSE,

    /**
     * A list of fixed-size vectors per point, as used by late-interaction models.
     */
    MULTIVECTOR,

    /**
     * Index/value pairs, as produced by sparse models such as SPLADE or BM25.
     */
    SPARSE
}
