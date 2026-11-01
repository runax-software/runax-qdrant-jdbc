package com.runaxsoftware.qdrant.jdbc.gateway.models;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One value of a payload field, with how many points hold it. Qdrant counts a point once per
 * value, so a point whose field is an array is counted under each of its elements.
 *
 * @param value the field's value, as JSON: a string, a whole number, or a boolean
 * @param count how many points hold it
 */
public record FacetHit(JsonNode value, long count) {
}
