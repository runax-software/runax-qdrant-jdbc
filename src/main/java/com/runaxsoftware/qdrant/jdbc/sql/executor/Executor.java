package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.resultset.ListResultSet;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
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

    /**
     * @param gateway where scroll plans read points from
     */
    public Executor(QdrantGateway gateway) {
        this(gateway::scroll);
    }

    Executor(Scroller scroller) {
        this.scroller = scroller;
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

        throw new SQLFeatureNotSupportedException(
            "Running a " + plan.getClass().getSimpleName() + " isn't supported yet");
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
