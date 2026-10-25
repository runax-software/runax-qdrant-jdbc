package com.runaxsoftware.qdrant.jdbc.gateway.models;

import java.util.List;
import java.util.Map;

/**
 * A collection's vectors, payload indexes, and size.
 *
 * @param name           the collection name
 * @param pointsCount    the number of points, as Qdrant last counted them
 * @param vectors        the dense and multivectors in Qdrant's order, then the sparse vectors
 * @param payloadIndexes payload field path to its index type
 */
public record QdrantCollection(
    String name,
    long pointsCount,
    List<VectorField> vectors,
    Map<String, PayloadIndexType> payloadIndexes) {
}
