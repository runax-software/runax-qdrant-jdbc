package com.runaxsoftware.qdrant.jdbc.system;

import java.sql.SQLException;
import java.util.List;

/**
 * Where a system table's rows come from, so the executor never calls Qdrant itself and its
 * tests can answer with rows built by hand.
 */
@FunctionalInterface
public interface SystemTableReader {

    /**
     * Every row of a system table, in the table's row order, each with one value per column in
     * {@link SystemTable#columns()} order.
     */
    List<Object[]> read(SystemTable table) throws SQLException;
}
