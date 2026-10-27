package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SelectPlanner {

    // The name PostgreSQL gives a SELECT item that isn't a column and has no alias
    private static final String UNNAMED = "?column?";

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

        TableSchema schema = catalog.table(statement.table());
        List<ColumnDefinition> columns = statement.items().isEmpty()
            ? schema.columns().stream().filter(x -> x.kind() != ColumnKind.VECTOR).toList()
            : resolve(statement.items(), schema);

        boolean withPayload = columns.stream().anyMatch(
            x -> x.kind() == ColumnKind.PAYLOAD || x.kind() == ColumnKind.PAYLOAD_FIELD);
        boolean withVector = columns.stream().anyMatch(x -> x.kind() == ColumnKind.VECTOR);

        return new ScrollPlan(schema.name(), columns, withPayload, withVector, statement.limit());
    }

    // A SELECT without FROM: every item is a constant, so the row needs no call to Qdrant
    private static ConstantPlan constants(SelectStatement statement) throws SQLException {
        if (statement.items().isEmpty()) {
            throw new SQLSyntaxErrorException("SELECT * needs a FROM", "42000");
        }
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
        if (expression instanceof Column column) {
            throw new SQLSyntaxErrorException(
                "Unknown column '" + column.name() + "': the SELECT has no FROM", "42S22");
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

    private static List<ColumnDefinition> resolve(
        List<SelectItem> items, TableSchema schema) throws SQLException {
        var columns = new ArrayList<ColumnDefinition>();

        for (SelectItem item : items) {
            if (!(item.expression() instanceof Column column)) {
                throw new SQLFeatureNotSupportedException(
                    "Constants in a SELECT with FROM aren't supported yet");
            }

            ColumnDefinition definition = schema.column(column.name()).orElseThrow(
                () -> new SQLSyntaxErrorException(
                    "Unknown column '" + column.name() + "' in " + schema.name(), "42S22"));

            columns.add(item.alias() == null ? definition : new ColumnDefinition(item.alias(),
                definition.kind(), definition.type(), definition.size(), definition.source(),
                definition.remarks()));
        }

        return columns;
    }
}
