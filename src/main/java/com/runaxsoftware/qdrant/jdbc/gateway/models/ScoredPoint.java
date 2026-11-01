package com.runaxsoftware.qdrant.jdbc.gateway.models;

/**
 * A point a search returned, with how close it was.
 *
 * @param point the point itself, with whatever payload and vectors were asked for
 * @param score the distance Qdrant scored it with, higher being closer whatever the
 *              collection's distance function
 */
public record ScoredPoint(Point point, double score) {
}
