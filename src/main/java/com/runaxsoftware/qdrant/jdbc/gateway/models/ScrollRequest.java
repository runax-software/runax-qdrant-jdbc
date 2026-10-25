package com.runaxsoftware.qdrant.jdbc.gateway.models;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One page request for Qdrant's scroll API.
 *
 * @param collection  the collection to read
 * @param limit       the maximum number of points to return
 * @param offset      the point ID to start from (a previous page's next offset), or {@code null}
 *                    to start at the beginning
 * @param withPayload whether to return payloads
 * @param withVector  whether to return vectors
 */
public record ScrollRequest(
    String collection,
    int limit,
    JsonNode offset,
    boolean withPayload,
    boolean withVector) {
}
