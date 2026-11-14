package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.runaxsoftware.qdrant.jdbc.gateway.models.MatrixPair;
import com.runaxsoftware.qdrant.jdbc.gateway.models.MatrixRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
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

    /**
     * A distance matrix: sampled points, each with its nearest neighbours among the sample. Only
     * a searcher that reaches Qdrant's matrix search has one.
     */
    default List<MatrixPair> matrix(MatrixRequest matrix) throws SQLException {
        throw new SQLFeatureNotSupportedException("This searcher has no distance matrix");
    }

    /**
     * Ranks the best points of each group: as many groups as the query's limit, each with up to
     * {@code groupSize} points, group after group. Only a searcher that reaches Qdrant's grouped
     * search can.
     */
    default List<ScoredPoint> searchGroups(QueryRequest query, String groupBy, long groupSize)
        throws SQLException {
        throw new SQLFeatureNotSupportedException("This searcher doesn't group");
    }
}
