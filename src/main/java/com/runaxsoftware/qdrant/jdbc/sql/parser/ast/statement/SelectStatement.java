package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.OrderItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.util.List;

/**
 * A SELECT. A {@code *} is one item like any other, so items always holds at least one; table,
 * alias, where, limit and offset are null when absent.
 *
 * @param items   the select list, in order
 * @param table   the table read, or {@code null} for a SELECT without FROM
 * @param alias   the name the statement gives that table, as in {@code FROM docs t}, or
 *                {@code null}
 * @param where   the WHERE condition, or {@code null}
 * @param groupBy the GROUP BY columns, in order, empty when absent
 * @param orderBy the ORDER BY columns, in order, empty when absent
 * @param limit   the LIMIT, or {@code null}
 * @param offset  the OFFSET, or {@code null}
 * @param perGroup the rows to keep of each group, from {@code LIMIT n BY column} or
 *                {@code DISTINCT ON (column)}, or {@code null}
 */
public record SelectStatement(
    List<SelectItem> items,
    TableName table,
    String alias,
    Expression where,
    List<Column> groupBy,
    List<OrderItem> orderBy,
    Long limit,
    Long offset,
    PerGroup perGroup
) implements Statement {

    /**
     * A SELECT that keeps every row of every group, which is every SELECT without
     * {@code LIMIT ... BY} or {@code DISTINCT ON}.
     */
    public SelectStatement(List<SelectItem> items, TableName table, String alias,
                           Expression where, List<Column> groupBy, List<OrderItem> orderBy,
                           Long limit, Long offset) {
        this(items, table, alias, where, groupBy, orderBy, limit, offset, null);
    }
}
