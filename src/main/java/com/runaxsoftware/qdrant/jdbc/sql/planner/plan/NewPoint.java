package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * One point an INSERT adds.
 *
 * @param id      its ID: the one the statement gave, or one the driver made
 * @param idGiven whether the statement gave the ID, which then mustn't be taken yet
 * @param payload its payload
 * @param vectors its vectors as Qdrant takes them, by name, {@code ""} for the unnamed one: a
 *                vector written out, or text for Qdrant to embed
 * @param embeds  its vectors the driver embeds before it inserts the point, by name
 */
public record NewPoint(JsonNode id, boolean idGiven, ObjectNode payload,
                       Map<String, JsonNode> vectors, Map<String, EmbedText> embeds) {

    public NewPoint {
        vectors = Map.copyOf(vectors);
        embeds = Map.copyOf(embeds);
    }
}
