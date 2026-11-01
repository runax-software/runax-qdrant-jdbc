package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;

import java.sql.SQLException;
import java.util.List;

/**
 * Ranks the points nearest a vector: {@code gateway::query} when running, or points scored by
 * hand in tests.
 */
@FunctionalInterface
interface Searcher {

    List<ScoredPoint> search(QueryRequest query) throws SQLException;
}
