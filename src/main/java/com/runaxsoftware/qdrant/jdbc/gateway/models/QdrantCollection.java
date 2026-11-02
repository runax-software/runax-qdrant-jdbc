package com.runaxsoftware.qdrant.jdbc.gateway.models;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A collection's vectors, payload indexes, and size.
 *
 * @param name           the collection name
 * @param pointsCount    the number of points, as Qdrant last counted them
 * @param vectors        the dense and multivectors in Qdrant's order, then the sparse vectors
 * @param payloadIndexes payload field path to its index type
 * @param metadata       the collection's metadata entries whose values are text, such as the
 *                       model each vector was embedded with
 * @param phraseFields   the text-indexed fields whose index was built with
 *                       {@code phrase_matching}, which is what a phrase match needs: without it
 *                       Qdrant answers one with no points rather than an error, so the driver
 *                       has to know before it asks
 */
public record QdrantCollection(
    String name,
    long pointsCount,
    List<VectorField> vectors,
    Map<String, PayloadIndexType> payloadIndexes,
    Map<String, String> metadata,
    Set<String> phraseFields) {

    /**
     * A collection without metadata.
     */
    public QdrantCollection(String name, long pointsCount, List<VectorField> vectors,
                            Map<String, PayloadIndexType> payloadIndexes) {
        this(name, pointsCount, vectors, payloadIndexes, Map.of());
    }

    /**
     * A collection none of whose text indexes matches phrases.
     */
    public QdrantCollection(String name, long pointsCount, List<VectorField> vectors,
                            Map<String, PayloadIndexType> payloadIndexes,
                            Map<String, String> metadata) {
        this(name, pointsCount, vectors, payloadIndexes, metadata, Set.of());
    }
}
