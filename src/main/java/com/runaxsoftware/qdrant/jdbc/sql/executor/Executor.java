package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetHit;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;
import com.runaxsoftware.qdrant.jdbc.resultset.ListResultSet;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CountPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DeletePointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetPlan;
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

    private static final String UNKNOWN_TABLE = "42S02";

    // What Qdrant answers when it's asked to embed with a model and has no inference service
    private static final String NO_INFERENCE = "InferenceService URL not configured";

    private final Scroller scroller;
    private final Searcher searcher;
    private final Counter counter;
    private final Faceter faceter;
    private final SystemTableReader systemTables;
    private final Writer writer;

    /**
     * @param gateway where scroll plans read points, query plans rank them, count and facet
     *                plans count them, and system table plans read the server's own state from
     */
    public Executor(QdrantGateway gateway) {
        this(gateway::scroll, gateway::query, gateway::count, gateway::facet,
            new QdrantSystemTables(gateway), writer(gateway));
    }

    /**
     * An executor that finds no search results, counts no points, and finds no system table
     * rows, for plans that read none of the three.
     */
    Executor(Scroller scroller) {
        this(scroller, query -> List.of(), (collection, filter) -> 0, facet -> List.of(),
            table -> List.of());
    }

    /**
     * An executor that runs no write plans.
     */
    Executor(Scroller scroller, Searcher searcher, Counter counter, Faceter faceter,
             SystemTableReader systemTables) {
        this(scroller, searcher, counter, faceter, systemTables, null);
    }

    Executor(Scroller scroller, Searcher searcher, Counter counter, Faceter faceter,
             SystemTableReader systemTables, Writer writer) {
        this.writer = writer;
        this.scroller = scroller;
        this.searcher = searcher;
        this.faceter = faceter;
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
            int pageSize;

            if (scroll.ordering() == null) {
                pageSize = fetchsize > 0 ? Math.min(fetchsize, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
            } else {
                limit = orderedLimit(limit);
                pageSize = onePage(limit);
            }

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

        if (plan instanceof FacetPlan facet) {
            long limit = groupLimit(facet, maxRows);

            return ExecutionResult.rows(new ListResultSet(resultColumns(facet),
                limit == 0 ? List.of() : facetRows(facet, limit), statement));
        }

        if (plan instanceof DeletePointsPlan delete) {
            return ExecutionResult.updated(delete(delete));
        }

        if (plan instanceof DropCollectionPlan drop) {
            drop(drop);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof CreateCollectionPlan create) {
            create(create);
            return ExecutionResult.updated(0);
        }

        if (plan instanceof SystemTablePlan system) {
            return ExecutionResult.rows(new ListResultSet(resultColumns(system),
                systemRows(system, limit(system.limit(), maxRows)), statement));
        }

        throw new SQLFeatureNotSupportedException(
            "Running a " + plan.getClass().getSimpleName() + " isn't supported yet");
    }

    /**
     * How many points an ordered scroll reads. Qdrant answers one in a single page, with no
     * next offset to read another, so one says how many: the statement's LIMIT, or its maximum
     * rows. A read that gives neither is an error rather than the first page the driver
     * happened to ask for.
     */
    private static long orderedLimit(Long limit) throws SQLException {
        if (limit == null) {
            throw new SQLSyntaxErrorException("ORDER BY needs a LIMIT: Qdrant answers an ordered "
                + "read in one page, so a statement says how many points to read", "42000");
        }

        return limit;
    }

    /**
     * One page holding every row an ordered read returns, since there's no second page to ask
     * for. The fetch size doesn't cut it down: that would drop rows the statement asked for.
     */
    private static int onePage(long limit) {
        return (int) Math.max(1, Math.min(limit, Integer.MAX_VALUE));
    }

    /**
     * Deletes the plan's points and returns how many there were. Qdrant doesn't say how many a
     * delete removed, so they're counted first: exact unless something writes to the collection
     * between the two calls.
     */
    private long delete(DeletePointsPlan plan) throws SQLException {
        long count = writer.count(plan.collection(), plan.filter());

        if (count > 0) {
            writer.deletePoints(plan.collection(), plan.filter());
        }

        return count;
    }

    private void drop(DropCollectionPlan plan) throws SQLException {
        try {
            writer.deleteCollection(plan.collection());
        } catch (SQLException e) {
            if (!plan.ifExists() || !UNKNOWN_TABLE.equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    /**
     * Creates the collection, then its indexes. Qdrant can't do both in one call, so when an
     * index fails the collection is deleted again: a CREATE TABLE either makes the whole table
     * or none of it.
     */
    private void create(CreateCollectionPlan plan) throws SQLException {
        if (plan.ifNotExists() && writer.collectionExists(plan.collection())) {
            return;
        }

        writer.createCollection(plan.collection(), plan.config());

        try {
            for (CreateCollectionPlan.PayloadIndex index : plan.indexes()) {
                writer.createIndex(plan.collection(), index.field(), index.type());
            }
        } catch (SQLException e) {
            try {
                writer.deleteCollection(plan.collection());
            } catch (SQLException undo) {
                e.addSuppressed(undo);
            }

            throw e;
        }
    }

    /**
     * The search's points. A Qdrant without an inference service can't embed a document with a
     * hosted model, and says so in terms of its own configuration, so the error says what to do.
     */
    private List<ScoredPoint> search(QueryRequest request) throws SQLException {
        try {
            return searcher.search(request);
        } catch (SQLException e) {
            if (request.document() == null || !String.valueOf(e.getMessage()).contains(NO_INFERENCE)) {
                throw e;
            }

            throw new SQLException("This Qdrant can't embed text with '"
                + request.document().model() + "': it runs no inference service, which hosted "
                + "models need (Qdrant Cloud has one). Every Qdrant can embed with qdrant/bm25, "
                + "into a sparse vector", e.getSQLState(), e);
        }
    }

    private static Writer writer(QdrantGateway gateway) {
        return new Writer() {
            @Override
            public long count(String collection, JsonNode filter) throws SQLException {
                return gateway.count(collection, filter);
            }

            @Override
            public void deletePoints(String collection, JsonNode filter) throws SQLException {
                gateway.deletePoints(collection, filter);
            }

            @Override
            public void deleteCollection(String collection) throws SQLException {
                gateway.deleteCollection(collection);
            }

            @Override
            public boolean collectionExists(String collection) throws SQLException {
                return gateway.collectionExists(collection);
            }

            @Override
            public void createCollection(String collection, JsonNode config) throws SQLException {
                gateway.createCollection(collection, config);
            }

            @Override
            public void createIndex(String collection, String field, String type)
                throws SQLException {
                gateway.createIndex(collection, field, type);
            }
        };
    }

    /**
     * The groups' rows, the most common value first, holding the value and the count in the
     * statement's order. One call answers the whole GROUP BY, so the rows are read at once.
     */
    private List<Object[]> facetRows(FacetPlan plan, long limit) throws SQLException {
        List<FacetHit> hits = faceter.facet(new FacetRequest(plan.collection(), plan.key(),
            limit, plan.filter()));
        List<FacetColumn> columns = plan.columns();
        var rows = new ArrayList<Object[]>(hits.size());

        for (FacetHit hit : hits) {
            Object[] row = new Object[columns.size()];

            for (int i = 0; i < row.length; i++) {
                FacetColumn column = columns.get(i);
                row[i] = column.counting() ? hit.count() : value(hit.value(), column.type());
            }

            rows.add(row);
        }

        return rows;
    }

    /**
     * A grouped value, as the column's type. Qdrant counts the values of a field indexed as a
     * keyword, a whole number, a bool, or a UUID, so each is one of those three kinds of JSON.
     */
    private static Object value(JsonNode value, ColumnType type) {
        if (value == null || value.isNull()) {
            return null;
        }

        return switch (type) {
            case INTEGER -> value.longValue();
            case BOOLEAN -> value.booleanValue();
            default -> value.asText();
        };
    }

    /**
     * How many groups a GROUP BY reads. Qdrant counts the most common values and returns no
     * cursor, so a statement says how many: its LIMIT, or its maximum rows. One that gives
     * neither is an error rather than the ten Qdrant counts by default.
     */
    private static long groupLimit(FacetPlan plan, long maxRows) throws SQLException {
        Long limit = limit(plan.limit(), maxRows);

        if (limit == null) {
            throw new SQLSyntaxErrorException("GROUP BY needs a LIMIT: Qdrant counts the most "
                + "common values of a field in one call, so a statement says how many groups to "
                + "read", "42000");
        }

        return Math.max(limit, 0);
    }

    private static List<ResultColumn> resultColumns(FacetPlan plan) {
        return plan.columns().stream()
            .map(x -> new ResultColumn(x.name(), x.type().sqlType(), x.type().typeName(),
                plan.collection(), 0,
                x.nullable() ? ResultSetMetaData.columnNullable : ResultSetMetaData.columnNoNulls))
            .toList();
    }

    /**
     * The search's rows, one per point Qdrant ranked, best first. One call answers the whole
     * search, so the rows are read at once rather than paged.
     */
    private List<Object[]> searchRows(QueryPlan plan, long limit) throws SQLException {
        List<ScoredPoint> found = search(new QueryRequest(plan.collection(), plan.vector(),
            plan.query(), limit, plan.withPayload(), plan.withVector(), plan.filter(),
            plan.document()));
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

        return List.<Object[]>of(new Object[]{counter.count(plan.collection(), plan.filter())});
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
