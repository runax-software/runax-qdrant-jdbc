package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.runaxsoftware.qdrant.jdbc.system.SystemColumn;
import com.runaxsoftware.qdrant.jdbc.system.SystemTable;

import java.util.List;

/**
 * Read one of the {@code system} schema's tables, which describe the server rather than any
 * collection's points. Their rows come from Qdrant's REST API, few enough to read at once.
 *
 * @param table   the system table to read
 * @param columns the result set's columns, in SELECT order
 * @param limit   the maximum number of rows, or {@code null} for no limit
 * @param offset  how many rows an OFFSET passes over, or 0 for none. The whole table is read
 *                into memory anyway, so skipping its first rows costs nothing beyond the call
 *                the statement makes regardless
 */
public record SystemTablePlan(SystemTable table, List<SystemColumn> columns, Long limit,
                              long offset) implements Plan {

    /**
     * A read that starts at the first row, which is every one without an OFFSET.
     */
    public SystemTablePlan(SystemTable table, List<SystemColumn> columns, Long limit) {
        this(table, columns, limit, 0);
    }
}
