package com.runaxsoftware.qdrant.jdbc.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One page of points from Qdrant's scroll API.
 *
 * @param points         the points on this page
 * @param nextPageOffset the point ID that starts the next page, or {@code null} on the last page
 */
public record ScrollPage(List<Point> points, JsonNode nextPageOffset) {
}
