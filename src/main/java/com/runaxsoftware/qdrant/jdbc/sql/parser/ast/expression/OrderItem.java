package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

/**
 * One entry of an ORDER BY: a column, which the statement may name under the table or an
 * alias, or a call that ranks the rows, such as {@code similarity(dense, [0.1, 0.2])}.
 *
 * @param expression what to order by
 * @param descending whether DESC was given
 */
public record OrderItem(Expression expression, boolean descending) {}
