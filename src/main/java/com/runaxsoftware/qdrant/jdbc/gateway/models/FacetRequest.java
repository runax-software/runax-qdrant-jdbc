package com.runaxsoftware.qdrant.jdbc.gateway.models;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One request for Qdrant's facet API: how many points hold each value of a payload field.
 *
 * @param collection the collection to count in
 * @param key        the payload field path to count the values of, which needs an index Qdrant
 *                   can facet on
 * @param limit      how many values to count, which a facet always says: Qdrant returns the
 *                   ten most common and no cursor when a request gives none
 * @param filter     the Qdrant filter the points must match, or {@code null} for all of them
 */
public record FacetRequest(String collection, String key, long limit, JsonNode filter) {
}
