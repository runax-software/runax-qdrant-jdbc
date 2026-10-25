package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;

import java.sql.SQLException;

/**
 * Looks up the collections a statement reads, so the planner itself never calls Qdrant and
 * its tests can use schemas built by hand.
 */
@FunctionalInterface
public interface Catalog {

    /**
     * The schema of a collection. Fails with SQL state {@code 42S02} when there's no such
     * collection.
     */
    TableSchema table(String name) throws SQLException;
}
