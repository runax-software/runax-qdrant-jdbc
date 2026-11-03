package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Change the payload and the vectors of the points a filter matches, through one call to
 * Qdrant's batch update API.
 *
 * <p>The payload operations are ready to send: Qdrant changes a payload by filter. Vectors
 * aren't, because Qdrant's {@code update_vectors} takes a list of point IDs and rejects a
 * filter alone, so the executor reads the matching IDs first and builds that operation. The
 * vectors a statement clears need no IDs: {@code delete_vectors} does take a filter.
 *
 * @param collection the collection whose points change
 * @param filter     the points to change, as a Qdrant filter
 * @param operations the payload operations, in order, each holding the filter
 * @param vectors    the vectors to write, by Qdrant's vector name ({@code ""} for the unnamed
 *                   one): a vector written out, or text for Qdrant to embed
 * @param embeds     the vectors the driver embeds before it writes them, by vector name
 * @param cleared    the vector names a statement sets to NULL, which are deleted by filter
 */
public record UpdatePointsPlan(String collection, JsonNode filter, List<JsonNode> operations,
                               Map<String, JsonNode> vectors, Map<String, EmbedText> embeds,
                               List<String> cleared) implements Plan {

    public UpdatePointsPlan {
        operations = List.copyOf(operations);
        vectors = Map.copyOf(vectors);
        embeds = Map.copyOf(embeds);
        cleared = List.copyOf(cleared);
    }

    /**
     * A plan that changes the payload only.
     */
    public UpdatePointsPlan(String collection, JsonNode filter, List<JsonNode> operations) {
        this(collection, filter, operations, Map.of(), Map.of(), List.of());
    }

    /**
     * Whether the statement writes a vector, which is what needs the matching point IDs read
     * first. Clearing one doesn't.
     */
    public boolean writesVectors() {
        return !vectors.isEmpty() || !embeds.isEmpty();
    }

    @Override
    public boolean returnsRows() {
        return false;
    }
}
