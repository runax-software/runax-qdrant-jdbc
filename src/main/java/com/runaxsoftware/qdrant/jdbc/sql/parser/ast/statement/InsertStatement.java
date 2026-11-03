package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

import java.util.List;

/**
 * An INSERT of new points, one per row of VALUES.
 *
 * @param table      the collection the points go into
 * @param columns    the columns the rows give values for, in order
 * @param rows       the rows, each with one value per column, in the same order
 * @param onConflict what to do with a row whose point ID is taken
 */
public record InsertStatement(TableName table, List<Column> columns, List<List<Expression>> rows,
                              OnConflict onConflict) implements Statement {

    public InsertStatement {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }

    /**
     * An INSERT with no ON CONFLICT clause, where a taken ID is an error.
     */
    public InsertStatement(TableName table, List<Column> columns, List<List<Expression>> rows) {
        this(table, columns, rows, OnConflict.FAIL);
    }
}
