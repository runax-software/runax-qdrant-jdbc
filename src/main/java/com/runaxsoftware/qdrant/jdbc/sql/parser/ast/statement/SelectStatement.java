package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.OrderItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.util.List;

/**
 * A SELECT. items is empty for SELECT *; table, where, limit and offset are null when absent.
 */
public record SelectStatement(
    List<SelectItem> items,
    TableName table,
    Expression where,
    List<OrderItem> orderBy,
    Long limit,
    Long offset
) implements Statement {}
