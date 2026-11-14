package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ranks the points nearest a vector: {@code gateway::query} when running, or points scored by
 * hand in tests.
 */
@FunctionalInterface
interface Searcher {

    List<ScoredPoint> search(QueryRequest query) throws SQLException;

    /**
     * Ranks the points for several searches of one collection, answering each in the order
     * given. One call when Qdrant runs them, which is the point of a batch; a searcher that
     * only knows how to run one search runs them one after another.
     */
    default List<List<ScoredPoint>> searchBatch(String collection, List<QueryRequest> queries)
        throws SQLException {
        var answers = new ArrayList<List<ScoredPoint>>(queries.size());
        for (QueryRequest query : queries) answers.add(search(query));

        return answers;
    }
}
