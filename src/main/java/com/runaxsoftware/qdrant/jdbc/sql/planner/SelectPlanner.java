package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.runaxsoftware.qdrant.jdbc.metadata.QdrantDatabaseMetaData;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Star;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
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
import java.util.stream.Collectors;

final class SelectPlanner {

    // The name PostgreSQL gives a SELECT item that isn't a column and has no alias
    private static final String UNNAMED = "?column?";

    // The catalog and the collections' schema, the names DatabaseMetaData reports
    private static final String CATALOG = QdrantDatabaseMetaData.CATALOG;
    private static final String COLLECTIONS_SCHEMA = QdrantDatabaseMetaData.SCHEMA;

    public Plan plan(SelectStatement statement, Catalog catalog) throws SQLException {
        if (statement.table() == null) {
            return constants(statement);
        }

        if (statement.where() != null) {
            throw new SQLFeatureNotSupportedException("WHERE isn't supported yet");
        }
        if (!statement.orderBy().isEmpty()) {
            throw new SQLFeatureNotSupportedException("ORDER BY isn't supported yet");
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

        TableSchema schema = catalog.table(table.name());
        List<ColumnDefinition> columns = resolve(statement.items(), schema, statement.alias());

        boolean withPayload = columns.stream().anyMatch(
            x -> x.kind() == ColumnKind.PAYLOAD || x.kind() == ColumnKind.PAYLOAD_FIELD);
        boolean withVector = columns.stream().anyMatch(x -> x.kind() == ColumnKind.VECTOR);

        return new ScrollPlan(schema.name(), columns, withPayload, withVector, statement.limit());
    }

    /**
     * One of the {@code system} schema's tables. Their columns are fixed, so this needs no
     * catalog lookup and never calls Qdrant.
     */
    private static Plan systemTable(SelectStatement statement, TableName name) throws SQLException {
        SystemTable table = SystemTable.named(name.name()).orElseThrow(
            () -> new SQLSyntaxErrorException("Unknown table '" + name.qualified() + "': the "
                + SystemTable.SCHEMA + " schema has " + systemTableNames(), "42S02"));

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
     * column but the vectors, which are fetched only when a statement names them. A column or
     * star may be named under the collection or the statement's alias for it.
     *
     * @param alias the name the statement gives the collection, or {@code null}
     */
    private static List<ColumnDefinition> resolve(
        List<SelectItem> items, TableSchema schema, String alias) throws SQLException {
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

            ColumnDefinition definition = find(schema, column, alias);

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
    private static ColumnDefinition find(TableSchema schema, Column column, String alias)
        throws SQLException {
        if (column.qualifier() == null || namesTable(column.qualifier(), alias, schema.name(), false)) {
            return schema.column(column.name()).orElseThrow(
                () -> unknownColumn(column.qualified(), schema.name()));
        }

        return schema.column(column.qualified()).orElseThrow(() -> payloadPath(column, schema)
            ? unknownColumn(column.qualified(), schema.name())
            : unknownTable(column.qualifier(), column.qualified(), alias, schema.name()));
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
