package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;

/**
 * One {@code column = value} of an UPDATE's SET.
 *
 * @param column the column to change
 * @param value  its new value: a constant, {@code NULL}, or a placeholder
 */
public record Assignment(Column column, Expression value) {}
