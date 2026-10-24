package com.runaxsoftware.qdrant.jdbc.client;

import java.util.List;
import java.util.Map;

/**
 * A collection's vectors, payload indexes, and size.
 *
 * @param name           the collection name
 * @param pointsCount    the number of points, as Qdrant last counted them
 * @param vectors        the dense and multivectors in Qdrant's order, then the sparse vectors
 * @param payloadIndexes payload field path to index type ({@code "keyword"}, {@code "integer"},
 *                       {@code "float"}, {@code "bool"}, {@code "datetime"}, {@code "text"},
 *                       {@code "uuid"}, or {@code "geo"})
 */
public record CollectionInfo(
    String name,
    long pointsCount,
    List<VectorField> vectors,
    Map<String, String> payloadIndexes) {
}
