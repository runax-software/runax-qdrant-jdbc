package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;
import com.runaxsoftware.qdrant.jdbc.resultset.ListResultSet;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CountPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SystemTablePlan;
import com.runaxsoftware.qdrant.jdbc.system.QdrantSystemTables;
import com.runaxsoftware.qdrant.jdbc.system.SystemTableReader;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Types;
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
    private final Searcher searcher;
    private final Counter counter;
    private final SystemTableReader systemTables;

    /**
     * @param gateway where scroll plans read points, query plans rank them, count plans count
     *                them, and system table plans read the server's own state from
     */
    public Executor(QdrantGateway gateway) {
        this(gateway::scroll, gateway::query, gateway::count, new QdrantSystemTables(gateway));
    }

    /**
     * An executor that finds no search results, counts no points, and finds no system table
     * rows, for plans that read none of the three.
     */
    Executor(Scroller scroller) {
        this(scroller, query -> List.of(), collection -> 0, table -> List.of());
    }

    Executor(Scroller scroller, Searcher searcher, Counter counter,
             SystemTableReader systemTables) {
        this.scroller = scroller;
        this.searcher = searcher;
        this.counter = counter;
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

        if (plan instanceof QueryPlan query) {
            long limit = searchLimit(query, maxRows);

            return ExecutionResult.rows(new ListResultSet(
                PointValues.resultColumns(query.columns(), query.collection()),
                limit == 0 ? List.of() : searchRows(query, limit), statement));
        }

        if (plan instanceof CountPlan count) {
            // A count is always a number, so its column takes no nulls, and it belongs to no
            // collection: it's one number about one, not a column of it
            var column = new ResultColumn(count.column(), Types.BIGINT, "BIGINT", "", 0,
                ResultSetMetaData.columnNoNulls);

            return ExecutionResult.rows(new ListResultSet(List.of(column),
                countRows(count, limit(count.limit(), maxRows)), statement));
        }

        if (plan instanceof SystemTablePlan system) {
            return ExecutionResult.rows(new ListResultSet(resultColumns(system),
                systemRows(system, limit(system.limit(), maxRows)), statement));
        }

        throw new SQLFeatureNotSupportedException(
            "Running a " + plan.getClass().getSimpleName() + " isn't supported yet");
    }

    /**
     * The search's rows, one per point Qdrant ranked, best first. One call answers the whole
     * search, so the rows are read at once rather than paged.
     */
    private List<Object[]> searchRows(QueryPlan plan, long limit) throws SQLException {
        List<ScoredPoint> found = searcher.search(new QueryRequest(plan.collection(),
            plan.vector(), plan.query(), limit, plan.withPayload(), plan.withVector(), plan.filter()));
        List<ColumnDefinition> columns = plan.columns();
        var rows = new ArrayList<Object[]>(found.size());

        for (ScoredPoint scored : found) {
            Object[] row = new Object[columns.size()];

            for (int i = 0; i < row.length; i++) {
                row[i] = PointValues.of(scored.point(), scored.score(), columns.get(i));
            }

            rows.add(row);
        }

        return rows;
    }

    /**
     * How many points a search asks Qdrant to rank: its LIMIT, or the statement's maximum rows,
     * whichever is smaller. Qdrant ranks the points nearest the vector rather than reading the
     * whole collection, so one of the two has to say how many, and a search without either is
     * an error instead of a number the driver picked.
     */
    private static long searchLimit(QueryPlan plan, long maxRows) throws SQLException {
        Long limit = limit(plan.limit(), maxRows);

        if (limit == null) {
            throw new SQLSyntaxErrorException("A similarity search needs a LIMIT: Qdrant ranks "
                + "the points nearest the vector, not the whole collection", "42000");
        }

        return Math.max(limit, 0);
    }

    /**
     * The count's single row, or no row at all when the statement asks for none: a limit of no
     * rows leaves it out, as it does the row of a SELECT without FROM, and then nothing is
     * counted either.
     */
    private List<Object[]> countRows(CountPlan plan, Long limit) throws SQLException {
        if (limit != null && limit <= 0) {
            return List.of();
        }

        return List.<Object[]>of(new Object[]{counter.count(plan.collection())});
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
