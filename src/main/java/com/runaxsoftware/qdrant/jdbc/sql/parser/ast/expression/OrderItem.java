package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

/**
 * One column of an ORDER BY, which the statement may name under the table or an alias.
 *
 * @param column     the column to order by
 * @param descending whether DESC was given
 */
public record OrderItem(Column column, boolean descending) {}
