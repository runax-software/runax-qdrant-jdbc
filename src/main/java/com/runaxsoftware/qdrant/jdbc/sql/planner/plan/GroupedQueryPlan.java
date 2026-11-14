package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * A search that keeps only the best points of each group, through Qdrant's grouped Query API:
 * the groups are the values of one payload field, and each gives its closest points.
 *
 * <p>The search's limit and offset still count rows, as they do in SQL. Qdrant counts groups
 * and has no offset for them, so the executor asks for as many groups as the rows could need
 * and takes the rows itself.
 *
 * @param search      the search itself: what to rank, filter, and return
 * @param groupBy     the payload field whose values are the groups, as Qdrant names it
 * @param groupSize   how many points of each group to keep
 * @param lowerIsBest whether the vector's distance ranks a lower score first, as Euclid and
 *                    Manhattan do, which says how to order points of different groups
 */
public record GroupedQueryPlan(QueryPlan search, String groupBy, long groupSize,
                               boolean lowerIsBest) implements Plan {
}
