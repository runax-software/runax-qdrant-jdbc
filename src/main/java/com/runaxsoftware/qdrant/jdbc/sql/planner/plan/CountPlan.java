package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * Count a collection's points with Qdrant's count API, which answers one number, so this plan
 * has one row of one column. Counting needs none of the collection's columns, so a count reads
 * no payloads, no vectors, and not even the collection's schema: an unknown collection is what
 * Qdrant reports when the count runs. Counting the points a WHERE matches comes later, as a
 * filter beside the collection.
 *
 * @param collection the collection to count
 * @param column     the name of the single column: the name the statement gave the count, or
 *                   the one the driver gives it
 * @param limit      the maximum number of rows, or {@code null} when the statement gave none.
 *                   A count is one row, so only a limit of none leaves it out
 */
public record CountPlan(String collection, String column, Long limit) implements Plan {}
