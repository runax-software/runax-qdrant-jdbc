package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Ordering;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.metadata.QdrantDatabaseMetaData;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.DriverColumns;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.FunctionCall;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.OrderItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Star;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.VectorLiteral;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CountPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SystemTablePlan;
import com.runaxsoftware.qdrant.jdbc.system.SystemColumn;
import com.runaxsoftware.qdrant.jdbc.system.SystemTable;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class SelectPlanner {

    // The name PostgreSQL gives a SELECT item that isn't a column and has no alias
    private static final String UNNAMED = "?column?";

    // The function a search orders by, lower case: the driver's own names ignore case
    private static final String SIMILARITY = "similarity";

    // The one aggregate Qdrant answers itself, lower case, and the name PostgreSQL gives its
    // column when the statement gives it none
    private static final String COUNT = "count";

    // The payload indexes Qdrant can order by: the ones that compare values as a range
    private static final Set<PayloadIndexType> RANGE_INDEXES = Set.of(
        PayloadIndexType.INTEGER, PayloadIndexType.FLOAT, PayloadIndexType.DATETIME);

    // The driver's score column, which a search has and a scroll doesn't
    private static final String SCORE = DriverColumns.SCORE.columnName();

    // The catalog and the collections' schema, the names DatabaseMetaData reports
    private static final String CATALOG = QdrantDatabaseMetaData.CATALOG;
    private static final String COLLECTIONS_SCHEMA = QdrantDatabaseMetaData.SCHEMA;

    /**
     * What a statement's ORDER BY asks Qdrant to rank by.
     *
     * @param vector the Qdrant name of the vector to search, empty for an unnamed one
     * @param query  the vector to search with
     */
    private record Search(String vector, List<Double> query) {
    }

    public Plan plan(SelectStatement statement, Catalog catalog) throws SQLException {
        if (statement.table() == null) {
            return constants(statement);
        }

        if (statement.offset() != null) {
            throw new SQLFeatureNotSupportedException("OFFSET isn't supported yet");
        }

        TableName table = statement.table();
        checkCatalog(table);

        return SystemTable.isSystemSchema(table.schema())
            ? systemTable(statement, table)
            : collection(statement, table, catalog);
    }

    // A collection, named on its own or in the schema the collections sit in
    private static Plan collection(SelectStatement statement, TableName table, Catalog catalog)
        throws SQLException {
        if (table.schema() != null && !table.schema().equalsIgnoreCase(COLLECTIONS_SCHEMA)) {
            throw new SQLSyntaxErrorException("Unknown schema '" + table.schema() + "': this "
                + "driver has " + COLLECTIONS_SCHEMA + " for the collections and "
                + SystemTable.SCHEMA + " for the server's own tables", "42S02");
        }

        SelectItem counted = counted(statement.items());
        if (counted != null) {
            return count(statement, table.name(), counted.alias());
        }

        TableSchema schema = catalog.table(table.name());
        OrderItem order = order(statement);
        Search search = order == null ? null : search(order, schema, statement.alias());
        List<ColumnDefinition> columns =
            resolve(statement.items(), schema, statement.alias(), search != null);

        boolean withPayload = columns.stream().anyMatch(
            x -> x.kind() == ColumnKind.PAYLOAD || x.kind() == ColumnKind.PAYLOAD_FIELD);
        boolean withVector = columns.stream().anyMatch(x -> x.kind() == ColumnKind.VECTOR);
        JsonNode filter = FilterPlanner.plan(statement.where(), schema.name(),
            column -> find(schema, column, statement.alias(), search != null));

        if (search != null) {
            return new QueryPlan(schema.name(), columns, search.vector(), search.query(),
                withPayload, withVector, statement.limit(), filter);
        }

        Ordering ordering = order == null
            ? null : ordering(order, schema, statement.alias());

        return new ScrollPlan(schema.name(), columns, withPayload, withVector, statement.limit(),
            filter, ordering);
    }

    /**
     * The one thing a statement orders by, or {@code null} when it orders by nothing. Qdrant
     * orders by one field or ranks by one score, so ordering by several is an error rather than
     * sorting the driver would have to do itself.
     */
    private static OrderItem order(SelectStatement statement) throws SQLException {
        if (statement.orderBy().isEmpty()) {
            return null;
        }
        if (statement.orderBy().size() > 1) {
            throw new SQLFeatureNotSupportedException(
                "Ordering by more than one thing isn't supported yet");
        }

        return statement.orderBy().get(0);
    }

    /**
     * The order a scroll reads points in: a payload field with a range index, which is what
     * Qdrant can order by, or {@code null} for its own order, by point ID.
     */
    private static Ordering ordering(OrderItem order, TableSchema schema, String alias)
        throws SQLException {
        if (!(order.expression() instanceof Column named)) {
            throw new SQLFeatureNotSupportedException("ORDER BY takes a column or "
                + SIMILARITY + "(vector, [0.1, ...]) DESC");
        }

        ColumnDefinition column = find(schema, named, alias, false);

        if (column.kind() == ColumnKind.ID) {
            if (order.descending()) {
                throw new SQLFeatureNotSupportedException("Qdrant reads points in order of their "
                    + "ID upwards, so ORDER BY " + column.name() + " DESC isn't supported");
            }

            return null;   // which is the order a scroll reads in anyway
        }

        if (column.kind() != ColumnKind.PAYLOAD_FIELD) {
            throw new SQLFeatureNotSupportedException("Ordering by '" + column.name() + "' of "
                + schema.name() + " isn't supported: Qdrant orders by a payload field");
        }

        PayloadIndexType index = schema.collection().payloadIndexes().get(column.source());

        // Set.of takes no null, and a field with no index at all is the common case
        if (index == null || !RANGE_INDEXES.contains(index)) {
            throw new SQLFeatureNotSupportedException("Ordering by '" + column.name() + "' of "
                + schema.name() + " needs a range index on it: Qdrant orders by a field indexed "
                + "as integer, float, or datetime, and this one is "
                + (index == null ? "not indexed" : "indexed as " + index.qdrantName()));
        }

        return new Ordering(column.source(), order.descending());
    }

    /**
     * The item a select list counts points with, {@code COUNT(*)} in any case and under any
     * name, or {@code null} when the list counts nothing. Qdrant's count API is the only
     * aggregate it runs, so every other call in a select list is an error here rather than work
     * the driver would have to do over a whole collection.
     */
    private static SelectItem counted(List<SelectItem> items) throws SQLException {
        for (SelectItem item : items) {
            if (!(item.expression() instanceof FunctionCall call)) {
                continue;
            }

            if (!call.is(COUNT)) {
                throw new SQLFeatureNotSupportedException("'" + call.name() + "' isn't an "
                    + "aggregate this driver has: Qdrant counts points, through COUNT(*), and "
                    + "computes no other aggregate over a collection");
            }
            if (call.arguments().size() != 1 || !(call.arguments().get(0) instanceof Star)) {
                throw new SQLFeatureNotSupportedException("COUNT takes a star, as COUNT(*): "
                    + "Qdrant counts the points of a collection, not the values of a field");
            }
            if (items.size() > 1) {
                throw new SQLFeatureNotSupportedException("COUNT(*) is a row of its own: a "
                    + "statement that counts a collection's points selects nothing besides it");
            }

            return item;
        }

        return null;
    }

    /**
     * A count of a collection's points. Qdrant's count API answers one number for the whole
     * collection, so the plan needs nothing but the collection's name and what to call the
     * column: no column of the collection is read, and none has to be looked up.
     *
     * @param alias the name the statement gives the count, or {@code null} for the driver's own
     */
    private static Plan count(SelectStatement statement, String collection, String alias)
        throws SQLException {
        if (statement.where() != null) {
            throw new SQLFeatureNotSupportedException("WHERE isn't supported in a count yet: "
                + "Qdrant's count API takes the same filter a read does, and the count doesn't "
                + "send one");
        }
        if (!statement.orderBy().isEmpty()) {
            throw new SQLFeatureNotSupportedException(
                "ORDER BY isn't supported in a count: COUNT(*) is one row, in no order");
        }

        return new CountPlan(collection, alias == null ? COUNT : alias, statement.limit());
    }

    /**
     * The search an ORDER BY asks for, or {@code null} when it orders by a column, which orders
     * a scroll instead.
     */
    private static Search search(OrderItem item, TableSchema schema, String alias)
        throws SQLException {
        if (!(item.expression() instanceof FunctionCall call)) {
            return null;   // a column, which orders a scroll instead
        }
        if (!call.is(SIMILARITY)) {
            throw new SQLFeatureNotSupportedException("'" + call.name() + "' isn't a function "
                + "this driver has; a search orders by " + SIMILARITY + "(vector, [0.1, ...]) "
                + "DESC");
        }
        if (!item.descending()) {
            throw new SQLFeatureNotSupportedException("A similarity search returns the closest "
                + "points first, so it needs ORDER BY " + SIMILARITY + "(...) DESC");
        }
        if (call.arguments().size() != 2) {
            throw new SQLSyntaxErrorException(SIMILARITY + " takes a vector column and a vector, "
                + "as " + SIMILARITY + "(dense, [0.1, 0.2])", "42000");
        }

        ColumnDefinition vector = searched(call.arguments().get(0), schema, alias);
        VectorLiteral query = vectorLiteral(call.arguments().get(1), vector);

        if (vector.size() != query.size()) {
            throw new SQLSyntaxErrorException("Vector '" + vector.name() + "' of " + schema.name()
                + " has " + vector.size() + " dimensions, the vector searching it has "
                + query.size(), "42000");
        }

        return new Search(vector.source(), query.values());
    }

    /**
     * The collection's vector a search reads, which is a vector column and not a payload field
     * or the point ID.
     */
    private static ColumnDefinition searched(Expression argument, TableSchema schema, String alias)
        throws SQLException {
        if (!(argument instanceof Column column)) {
            throw new SQLSyntaxErrorException("A similarity search names the vector to search "
                + "first, as " + SIMILARITY + "(dense, [0.1, 0.2])", "42000");
        }

        ColumnDefinition definition = find(schema, column, alias, false);

        if (definition.kind() != ColumnKind.VECTOR) {
            throw new SQLSyntaxErrorException("Column '" + definition.name() + "' of "
                + schema.name() + " isn't a vector: a similarity search searches a vector column",
                "42000");
        }
        if (definition.type() != ColumnType.VECTOR) {
            throw new SQLFeatureNotSupportedException("Searching '" + definition.name() + "', a "
                + definition.type().typeName() + " of " + schema.name()
                + ", isn't supported yet: a vector written out searches a dense vector");
        }

        return definition;
    }

    /**
     * The vector a search searches with. Only a vector written out is supported so far: text to
     * embed and the other forms a search could take come later.
     */
    private static VectorLiteral vectorLiteral(Expression argument, ColumnDefinition vector)
        throws SQLException {
        if (argument instanceof VectorLiteral literal) {
            return literal;
        }
        if (argument instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("Parameters aren't supported yet");
        }
        if (argument instanceof FunctionCall call) {
            throw new SQLFeatureNotSupportedException("'" + call.name() + "' isn't supported yet: "
                + "a similarity search takes a vector written out, as [0.1, 0.2]");
        }

        throw new SQLSyntaxErrorException("A similarity search takes a vector written out in "
            + "brackets, as " + SIMILARITY + "(" + vector.name() + ", [0.1, 0.2])", "42000");
    }

    /**
     * One of the {@code system} schema's tables. Their columns are fixed, so this needs no
     * catalog lookup and never calls Qdrant.
     */
    private static Plan systemTable(SelectStatement statement, TableName name) throws SQLException {
        if (statement.where() != null) {
            throw new SQLFeatureNotSupportedException(
                "WHERE isn't supported yet on the " + SystemTable.SCHEMA + " schema's tables");
        }
        if (!statement.orderBy().isEmpty()) {
            throw new SQLFeatureNotSupportedException("ORDER BY isn't supported yet");
        }

        SystemTable table = SystemTable.named(name.name()).orElseThrow(
            () -> new SQLSyntaxErrorException("Unknown table '" + name.qualified() + "': the "
                + SystemTable.SCHEMA + " schema has " + systemTableNames(), "42S02"));

        if (counted(statement.items()) != null) {
            throw new SQLFeatureNotSupportedException("Counting " + name.qualified() + " isn't "
                + "supported: Qdrant counts the points of a collection, and a table of the "
                + SystemTable.SCHEMA + " schema describes the server instead");
        }

        List<SystemColumn> columns = resolve(statement.items(), table, statement.alias());

        return new SystemTablePlan(table, columns, statement.limit());
    }

    // Qdrant has no catalogs, so the driver's one catalog is the only name a statement can give
    private static void checkCatalog(TableName table) throws SQLException {
        if (table.catalog() != null && !table.catalog().equalsIgnoreCase(CATALOG)) {
            throw new SQLSyntaxErrorException("Unknown catalog '" + table.catalog()
                + "': this driver has only " + CATALOG, "42S02");
        }
    }

    private static String systemTableNames() {
        return Arrays.stream(SystemTable.values())
            .map(SystemTable::tableName)
            .sorted()
            .collect(Collectors.joining(", "));
    }

    // A SELECT without FROM: every item is a constant, so the row needs no call to Qdrant
    private static ConstantPlan constants(SelectStatement statement) throws SQLException {
        if (statement.where() != null || !statement.orderBy().isEmpty()) {
            throw new SQLFeatureNotSupportedException(
                "WHERE and ORDER BY aren't supported in a SELECT without FROM");
        }

        var columns = new ArrayList<ResultColumn>();
        var row = new ArrayList<Object>();

        for (SelectItem item : statement.items()) {
            Object value = constant(item.expression());

            columns.add(ResultColumn.of(item.alias() != null ? item.alias() : UNNAMED, sqlType(value)));
            row.add(value);
        }

        boolean hasRow = (statement.limit() == null || statement.limit() > 0)
            && (statement.offset() == null || statement.offset() == 0);

        return new ConstantPlan(columns, hasRow ? List.of(Collections.unmodifiableList(row)) : List.of());
    }

    private static Object constant(Expression expression) throws SQLException {
        if (expression instanceof Literal literal) {
            return literal.value();
        }
        if (expression instanceof Star star) {
            throw new SQLSyntaxErrorException("SELECT " + star.qualified() + " needs a FROM",
                "42000");
        }
        if (expression instanceof Column column) {
            throw new SQLSyntaxErrorException(
                "Unknown column '" + column.qualified() + "': the SELECT has no FROM", "42S22");
        }
        if (expression instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("Parameters aren't supported yet");
        }

        throw new SQLFeatureNotSupportedException(
            "Only constants are supported in a SELECT without FROM");
    }

    private static int sqlType(Object value) {
        if (value instanceof Long) {
            return Types.BIGINT;
        }
        if (value instanceof Double) {
            return Types.DOUBLE;
        }
        if (value instanceof Boolean) {
            return Types.BOOLEAN;
        }

        return Types.VARCHAR;
    }

    /**
     * The system table columns a select list reads, in its order: a star stands for all of
     * them. A column or star may be named under the table or the statement's alias for it.
     *
     * @param alias the name the statement gives the table, or {@code null}
     */
    private static List<SystemColumn> resolve(
        List<SelectItem> items, SystemTable table, String alias) throws SQLException {
        String name = SystemTable.SCHEMA + "." + table.tableName();
        var columns = new ArrayList<SystemColumn>();

        for (SelectItem item : items) {
            if (item.expression() instanceof Star star) {
                checkQualifier(star.qualifier(), star.qualified(), alias, table.tableName(), name,
                    true);
                columns.addAll(table.columns());
                continue;
            }

            if (!(item.expression() instanceof Column column)) {
                throw new SQLFeatureNotSupportedException(
                    "Constants in a SELECT with FROM aren't supported yet");
            }

            checkQualifier(column.qualifier(), column.qualified(), alias, table.tableName(), name,
                true);

            SystemColumn definition = table.column(column.name()).orElseThrow(
                () -> unknownColumn(column.qualified(), name));

            columns.add(item.alias() == null ? definition : definition.as(item.alias()));
        }

        return columns;
    }

    /**
     * The collection's columns a select list reads, in its order: a star stands for every
     * column but the vectors, which are fetched only when a statement names them, and the
     * score, which a collection has no column for. A column or star may be named under the
     * collection or the statement's alias for it.
     *
     * @param alias     the name the statement gives the collection, or {@code null}
     * @param searching whether the statement is a search, which is what has a score
     */
    private static List<ColumnDefinition> resolve(List<SelectItem> items, TableSchema schema,
                                                  String alias, boolean searching)
        throws SQLException {
        var columns = new ArrayList<ColumnDefinition>();

        for (SelectItem item : items) {
            if (item.expression() instanceof Star star) {
                checkQualifier(star.qualifier(), star.qualified(), alias, schema.name(),
                    schema.name(), false);
                schema.columns().stream()
                    .filter(x -> x.kind() != ColumnKind.VECTOR)
                    .forEach(columns::add);
                continue;
            }

            if (!(item.expression() instanceof Column column)) {
                throw new SQLFeatureNotSupportedException(
                    "Constants in a SELECT with FROM aren't supported yet");
            }

            ColumnDefinition definition = find(schema, column, alias, searching);

            columns.add(item.alias() == null ? definition : new ColumnDefinition(item.alias(),
                definition.kind(), definition.type(), definition.size(), definition.source(),
                definition.remarks()));
        }

        return columns;
    }

    /**
     * The collection column a select item names. A qualifier that doesn't name the collection
     * may still be the first part of a nested payload field written without quotes, as
     * {@code specs.color} is for the column {@code "specs.color"}, so the whole name is looked
     * up before the qualifier is reported as unknown.
     */
    private static ColumnDefinition find(TableSchema schema, Column column, String alias,
                                         boolean searching) throws SQLException {
        if (column.qualifier() == null || namesTable(column.qualifier(), alias, schema.name(), false)) {
            if (column.name().equals(SCORE)) {
                return score(searching);
            }

            return schema.column(column.name()).orElseThrow(
                () -> unknownColumn(column.qualified(), schema.name()));
        }

        return schema.column(column.qualified()).orElseThrow(() -> payloadPath(column, schema)
            ? unknownColumn(column.qualified(), schema.name())
            : unknownTable(column.qualifier(), column.qualified(), alias, schema.name()));
    }

    /**
     * The score column, which only a search has: the points a scroll reads are in the
     * collection's own order and no closer to anything.
     */
    private static ColumnDefinition score(boolean searching) throws SQLException {
        if (!searching) {
            throw new SQLSyntaxErrorException("Column '" + SCORE + "' is only in a similarity "
                + "search: add ORDER BY " + SIMILARITY + "(vector, [0.1, ...]) DESC", "42S22");
        }

        return ColumnDefinition.score();
    }

    /**
     * Whether the name reads as a nested payload field rather than a column of another table:
     * the collection has fields under that path, so the field itself is what's missing.
     */
    private static boolean payloadPath(Column column, TableSchema schema) {
        String path = column.qualifier() + ".";

        return schema.columns().stream().anyMatch(x -> x.name().startsWith(path));
    }

    /**
     * Fails unless the qualifier names the table the statement reads.
     *
     * @param written    the name as the statement wrote it, for the error
     * @param alias      the name the statement gives the table, or {@code null}
     * @param table      the table's own name
     * @param reported   how to name the table in the error
     * @param ignoreCase whether the table's own name matches in any case, as the driver's own
     *                   names do and a collection name doesn't
     */
    private static void checkQualifier(String qualifier, String written, String alias,
                                       String table, String reported, boolean ignoreCase)
        throws SQLException {
        if (qualifier != null && !namesTable(qualifier, alias, table, ignoreCase)) {
            throw unknownTable(qualifier, written, alias, reported);
        }
    }

    /**
     * Whether a qualifier names the table a statement reads: the name the statement gives it
     * when it gives one, and the table's own name otherwise, as SQL does.
     */
    private static boolean namesTable(String qualifier, String alias, String table,
                                      boolean ignoreCase) {
        if (alias != null) {
            return qualifier.equals(alias);
        }

        return ignoreCase ? qualifier.equalsIgnoreCase(table) : qualifier.equals(table);
    }

    private static SQLSyntaxErrorException unknownColumn(String column, String table) {
        return new SQLSyntaxErrorException("Unknown column '" + column + "' in " + table, "42S22");
    }

    private static SQLSyntaxErrorException unknownTable(String qualifier, String written,
                                                       String alias, String table) {
        return new SQLSyntaxErrorException("Unknown table or alias '" + qualifier + "' in '"
            + written + "': this statement reads " + table
            + (alias == null ? "" : " as " + alias), "42S02");
    }
}
