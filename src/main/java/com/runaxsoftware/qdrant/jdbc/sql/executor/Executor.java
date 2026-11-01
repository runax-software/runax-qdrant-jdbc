package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.resultset.ListResultSet;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SystemTablePlan;
import com.runaxsoftware.qdrant.jdbc.system.QdrantSystemTables;
import com.runaxsoftware.qdrant.jdbc.system.SystemTableReader;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a plan and returns what it produced.
 */
public final class Executor {
    /**
     * Points per scroll request when the statement has no fetch size.
     */
    static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * The most points one scroll request asks for, whatever the fetch size: a page is one HTTP
     * response held in memory at once.
     */
    static final int MAX_PAGE_SIZE = 1_000;

    private final Scroller scroller;
    private final SystemTableReader systemTables;

    /**
     * @param gateway where scroll plans read points and system table plans read the server's
     *                own state from
     */
    public Executor(QdrantGateway gateway) {
        this(gateway::scroll, new QdrantSystemTables(gateway));
    }

    /**
     * An executor whose system tables hold no rows, for plans that read none.
     */
    Executor(Scroller scroller) {
        this(scroller, table -> List.of());
    }

    Executor(Scroller scroller, SystemTableReader systemTables) {
        this.scroller = scroller;
        this.systemTables = systemTables;
    }

    /**
     * @param plan      the plan to run
     * @param statement the statement the result belongs to
     */
    public ExecutionResult execute(Plan plan, Statement statement) throws SQLException {
        long maxRows = statement.getLargeMaxRows();
        int fetchsize = statement.getFetchSize();

        if (plan instanceof ConstantPlan constant) {
            List<Object[]> rows = constant.rows().stream()
                .limit(maxRows > 0 ? maxRows : Long.MAX_VALUE)
                .map(List::toArray)
                .toList();
            return ExecutionResult.rows(new ListResultSet(constant.columns(), rows, statement));
        }

        if (plan instanceof ScrollPlan scroll) {
            Long limit = limit(scroll.limit(), maxRows);
            int pageSize = fetchsize > 0 ? Math.min(fetchsize, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

            return ExecutionResult.rows(new ScrollResultSet(scroller, scroll, limit, pageSize, statement));
        }

        if (plan instanceof SystemTablePlan system) {
            return ExecutionResult.rows(new ListResultSet(resultColumns(system),
                systemRows(system, limit(system.limit(), maxRows)), statement));
        }

        throw new SQLFeatureNotSupportedException(
            "Running a " + plan.getClass().getSimpleName() + " isn't supported yet");
    }

    /**
     * The system table's rows, cut to the limit and holding only the columns the statement
     * asked for, in its order. A system table is small and its rows describe one moment, so
     * they're read at once rather than paged.
     */
    private List<Object[]> systemRows(SystemTablePlan plan, Long limit) throws SQLException {
        if (limit != null && limit <= 0) {
            return List.of();
        }

        int[] sources = plan.columns().stream().mapToInt(plan.table()::indexOf).toArray();
        List<Object[]> rows = new ArrayList<>();

        for (Object[] row : systemTables.read(plan.table())) {
            if (limit != null && rows.size() >= limit) {
                break;
            }

            Object[] projected = new Object[sources.length];
            for (int i = 0; i < sources.length; i++) projected[i] = row[sources[i]];

            rows.add(projected);
        }

        return rows;
    }

    private static List<ResultColumn> resultColumns(SystemTablePlan plan) {
        return plan.columns().stream()
            .map(x -> new ResultColumn(
                x.name(),
                x.type().sqlType(),
                x.type().typeName(),
                plan.table().tableName(),
                0,
                x.nullable()
                    ? ResultSetMetaData.columnNullable
                    : ResultSetMetaData.columnNoNulls))
            .toList();
    }

    private static Long limit(Long sqlLimit, long maxRows) {
        if (maxRows <= 0) {
            return sqlLimit;
        }

        return sqlLimit == null
            ? maxRows
            : Math.min(sqlLimit, maxRows);
    }
}
