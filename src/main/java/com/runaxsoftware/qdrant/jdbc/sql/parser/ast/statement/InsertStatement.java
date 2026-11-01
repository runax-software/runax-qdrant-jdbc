package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

import java.util.List;

/**
 * An INSERT of new points, one per row of VALUES.
 *
 * @param table   the collection the points go into
 * @param columns the columns the rows give values for, in order
 * @param rows    the rows, each with one value per column, in the same order
 */
public record InsertStatement(TableName table, List<Column> columns, List<List<Expression>> rows)
    implements Statement {

    public InsertStatement {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }
}
