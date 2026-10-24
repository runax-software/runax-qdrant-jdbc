package com.runaxsoftware.qdrant.jdbc.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A point, with its payload and vectors as JSON.
 *
 * @param id      the point ID: a number (an unsigned 64-bit integer) or a UUID string
 * @param payload the payload object, or {@code null} when it wasn't requested
 * @param vector  the vector or map of named vectors, or {@code null} when not requested
 */
public record Point(JsonNode id, JsonNode payload, JsonNode vector) {
}
