package com.runaxsoftware.qdrant.jdbc.statement;

import com.runaxsoftware.qdrant.jdbc.connection.QdrantConnection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOption;
import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOptions;
import com.runaxsoftware.qdrant.jdbc.resultset.ListResultSet;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.SchemaReader;
import com.runaxsoftware.qdrant.jdbc.sql.executor.ExecutionResult;
import com.runaxsoftware.qdrant.jdbc.sql.executor.Executor;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DeleteStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.UpdateStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.Planner;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.AddVectorPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropVectorPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.InsertPointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.RenameViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SetOptionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ShowOptionsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdatePointsPlan;
import com.runaxsoftware.qdrant.jdbc.system.SystemTable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A statement. SQL is parsed and planned against the collections' columns, so syntax errors,
 * unknown collections and unknown columns are reported before anything runs. A SELECT from a
 * collection reads its points page by page; a {@code COUNT(*)} of one asks Qdrant to count them
 * instead; a SELECT from one of the {@code system} schema's tables reads the server's own state
 * at once; a SELECT without FROM (such as a tool's {@code SELECT 'keep alive'}) returns its row.
 * The statement's maximum rows limit every result, and its fetch size sets how many points one
 * page reads. INSERT, UPDATE, DELETE, TRUNCATE, and the statements about tables, indexes and
 * views change what Qdrant holds and return a count instead, unless the connection is
 * read-only.
 *
 * <p>The columns come from the connection's {@link SchemaReader}, which keeps them for a while,
 * so they can be older than the collection. When the SQL names a column the kept columns don't
 * have, the statement reads the collection's columns again and plans once more before it
 * reports the column as unknown, so a field added to the collection since is found.
 */
public class QdrantStatement extends AbstractStatement {

    private static final String UNKNOWN_COLUMN = "42S22";

    private final Planner planner = new Planner();
    private final Executor executor;
    private final SchemaReader schemaReader;
    private final QdrantConnection connection;
    private List<String> generatedKeys = List.of();

    public QdrantStatement(QdrantConnection connection) {
        super(connection);
        this.connection = connection;
        this.schemaReader = connection.schemaReader();
        this.executor = new Executor(connection.gateway(), connection.embedders(),
            connection.defaultLimit());
    }

    // Running SQL

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkOpen();
        List<Statement> statements = parseAll(sql);

        return statements.size() == 1 ? query(plan(statements.get(0)))
            : runSearches(statements).resultSet();
    }

    /**
     * Runs a plan that returns rows, and refuses one that changes what Qdrant holds before it
     * runs.
     */
    ResultSet query(Plan plan) throws SQLException {
        if (!plan.returnsRows()) {
            throw new SQLException("executeQuery can only run a SELECT: use executeUpdate or "
                + "execute", "HY000");
        }

        return run(plan).resultSet();
    }

    /**
     * Runs the SQL, which may be several similarity searches of one collection separated by
     * semicolons: those go to Qdrant as one batch, and each one's rows are a result of its own,
     * the first here and the rest through {@link #getMoreResults()}.
     */
    @Override
    public boolean execute(String sql) throws SQLException {
        checkOpen();
        List<Statement> statements = parseAll(sql);

        return statements.size() == 1 ? run(plan(statements.get(0))).hasResultSet()
            : runSearches(statements).hasResultSet();
    }

    boolean execute(Plan plan) throws SQLException {
        return run(plan).hasResultSet();
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return execute(sql);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return (int) Math.min(executeLargeUpdate(sql), Integer.MAX_VALUE);
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        checkOpen();
        return update(plan(sql));
    }

    /**
     * Runs a plan that changes what Qdrant holds, and refuses a SELECT before it runs.
     */
    long update(Plan plan) throws SQLException {
        if (plan.returnsRows()) {
            throw new SQLException("executeUpdate can't run a SELECT: use executeQuery or execute",
                "HY000");
        }

        return run(plan).updateCount();
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeLargeUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeLargeUpdate(sql);
    }

    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeLargeUpdate(sql);
    }

    /**
     * Runs several statements as one batch of searches, and keeps every result after the first
     * for getMoreResults. Only searches of one collection can share a call, and nothing else has
     * a reason to: several writes in one string would stop halfway on an error with no way to
     * say how far they got.
     */
    private ExecutionResult runSearches(List<Statement> statements) throws SQLException {
        var plans = new ArrayList<QueryPlan>(statements.size());

        for (int i = 0; i < statements.size(); i++) {
            Plan plan = plan(statements.get(i));

            if (!(plan instanceof QueryPlan search)) {
                throw new SQLFeatureNotSupportedException("Several statements in one call run "
                    + "as a batch of similarity searches, and statement " + (i + 1) + " isn't "
                    + "one: each needs ORDER BY similarity(...) DESC. Run other statements one "
                    + "at a time");
            }
            if (!plans.isEmpty() && !plans.get(0).collection().equals(search.collection())) {
                throw new SQLFeatureNotSupportedException("A batch of searches is of one "
                    + "collection: statement 1 searches " + plans.get(0).collection()
                    + " and statement " + (i + 1) + " " + search.collection()
                    + ". Run searches of different collections one at a time");
            }

            plans.add(search);
        }

        closeResults();
        setUpdateCount(-1);
        generatedKeys = List.of();

        List<ExecutionResult> results = executor.executeSearches(plans, this);
        setResultSet(results.get(0).resultSet());
        setPendingResults(results.stream().skip(1).map(ExecutionResult::resultSet).toList());

        for (ExecutionResult result : results) {
            if (result.warning() != null) warn(result.warning());
        }

        return results.get(0);
    }

    // Runs the plan; like every JDBC run, it first closes the results of the previous one
    private ExecutionResult run(Plan plan) throws SQLException {
        closeResults();
        setUpdateCount(-1);
        generatedKeys = List.of();

        if (plan.changesData() && connection.isReadOnly()) {
            throw new SQLException("The connection is read-only, so it can't change what Qdrant "
                + "holds: call setReadOnly(false), or turn off read-only in the tool", "25006");
        }

        // Read before the plan runs: dropping a collection deletes the aliases that showed it
        Set<String> stale = stale(plan);

        ExecutionResult result = plan instanceof SetOptionPlan set ? setOption(set)
            : plan instanceof ShowOptionsPlan show ? showOptions(show)
            : executor.execute(plan, this);
        setResultSet(result.resultSet());
        setUpdateCount(result.updateCount());
        generatedKeys = result.keys();

        if (result.warning() != null) {
            warn(result.warning());
        }

        if (!stale.isEmpty()) {
            schemaReader.forget(stale::contains);
        }
        if (changesNames(plan)) {
            schemaReader.forgetLists();
        }

        return result;
    }

    /**
     * The names whose kept columns a plan makes wrong. A collection's columns are kept under its
     * own name and under each alias's name that read it, so a change to the collection, such as
     * an index that changes a column's type, forgets them under every one; Qdrant lists the
     * aliases of one collection, so only those are forgotten. A write that may add a field
     * forgets the collection's own name only: a read through an alias that names the new field
     * reads its columns again by itself, and looking the aliases up would cost a call per write.
     */
    private Set<String> stale(Plan plan) {
        if (plan instanceof CreateCollectionPlan create) return Set.of(create.collection());

        // A write may add a field, which the kept columns don't have yet
        if (plan instanceof InsertPointsPlan insert) return Set.of(insert.collection());
        if (plan instanceof UpdatePointsPlan update) return Set.of(update.collection());
        if (plan instanceof DropCollectionPlan drop) return withAliases(drop.collection());
        if (plan instanceof CreateIndexPlan index) return withAliases(index.collection());
        if (plan instanceof DropIndexPlan index) return withAliases(index.collection());
        if (plan instanceof AddVectorPlan add) return withAliases(add.collection());

        // The embedding model of a vector is metadata, which the kept columns carry
        if (plan instanceof UpdateCollectionPlan update) return withAliases(update.collection());
        if (plan instanceof DropVectorPlan drop) return withAliases(drop.collection());
        if (plan instanceof CreateViewPlan view) return Set.of(view.view());
        if (plan instanceof RenameViewPlan view) return Set.of(view.view(), view.newName());
        if (plan instanceof DropViewPlan view) return Set.of(view.view());

        return Set.of();
    }

    // A collection and the aliases that show it; a collection Qdrant doesn't have has none
    private Set<String> withAliases(String collection) {
        var names = new HashSet<String>();
        names.add(collection);

        try {
            names.addAll(connection.gateway().collectionAliases(collection));
        } catch (SQLException e) {
            // The statement reports a missing collection itself
        }

        return names;
    }

    // Whether the plan adds, removes, or renames a table or view, which the kept lists name
    private static boolean changesNames(Plan plan) {
        return plan instanceof CreateCollectionPlan || plan instanceof DropCollectionPlan
            || plan instanceof CreateViewPlan || plan instanceof RenameViewPlan
            || plan instanceof DropViewPlan;
    }

    /**
     * The IDs of the points the latest INSERT added, as one {@code _id} column: the ones the
     * statement gave, and the UUIDs the driver made for rows without one. Empty after anything
     * else.
     */
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();
        var rows = new ArrayList<Object[]>();
        for (String key : generatedKeys) rows.add(new Object[] {key});

        return new ListResultSet(List.of(ResultColumn.of("_id", Types.VARCHAR)), rows, this);
    }

    /**
     * Changes the connection's request options. They belong to the connection, so every
     * statement it makes from now on sends them, not only this one.
     */
    private ExecutionResult setOption(SetOptionPlan plan) throws SQLException {
        RequestOptions options = plan.option() == null
            ? RequestOptions.NONE
            : connection.requestOptions().with(plan.option(), plan.value());

        connection.requestOptions(options);
        return ExecutionResult.updated(0);
    }

    // One row per option: its name, and its value as text, or null for Qdrant's default
    private ExecutionResult showOptions(ShowOptionsPlan plan) {
        RequestOptions options = connection.requestOptions();
        var rows = new ArrayList<Object[]>();

        for (RequestOption option : plan.options()) {
            Object value = option == RequestOption.WAIT ? options.waits() : options.get(option);
            rows.add(new Object[] {"qdrant." + option.key(), value == null ? null : value.toString()});
        }

        return ExecutionResult.rows(new ListResultSet(List.of(
            ResultColumn.of("name", Types.VARCHAR), ResultColumn.of("setting", Types.VARCHAR)),
            rows, this));
    }

    // Reports syntax errors, unknown names, and SQL that can't run yet before anything runs
    private Plan plan(String sql) throws SQLException {
        return plan(parse(sql));
    }

    static Statement parse(String sql) throws SQLException {
        if (sql == null) {
            throw new SQLException("The SQL is null", "HY009");
        }

        return Parser.parse(sql);
    }

    // Every statement in the SQL: one, or several searches to run as a batch
    private static List<Statement> parseAll(String sql) throws SQLException {
        if (sql == null) {
            throw new SQLException("The SQL is null", "HY009");
        }

        return Parser.parseAll(sql);
    }

    /**
     * The plan for a parsed statement, checked against the collections' columns.
     */
    Plan plan(Statement statement) throws SQLException {
        Plan plan;

        try {
            plan = planner.plan(statement, schemaReader::read);
        } catch (SQLException e) {
            String collection = collectionRead(statement);

            if (collection == null || !UNKNOWN_COLUMN.equals(e.getSQLState())) {
                throw e;
            }

            // The kept columns may be older than the collection: read them again, once
            schemaReader.forget(collection::equals);
            plan = planner.plan(statement, schemaReader::read);
        }

        return plan;
    }

    /**
     * The collection a statement reads, or {@code null} when it reads none: a system table's
     * columns are the driver's own and never go out of date, so planning one again would find
     * the same unknown column.
     */
    private static String collectionRead(Statement statement) {
        TableName table = statement instanceof SelectStatement select ? select.table()
            : statement instanceof DeleteStatement delete ? delete.table()
            : statement instanceof UpdateStatement update ? update.table()
            : null;

        return table == null || SystemTable.isSystemSchema(table.schema()) ? null : table.name();
    }
}
