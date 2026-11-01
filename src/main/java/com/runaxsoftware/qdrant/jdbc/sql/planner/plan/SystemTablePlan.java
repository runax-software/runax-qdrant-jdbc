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
 */
public record SystemTablePlan(SystemTable table, List<SystemColumn> columns, Long limit)
    implements Plan {}
