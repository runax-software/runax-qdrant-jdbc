package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

/**
 * column BETWEEN low AND high, or NOT BETWEEN when negated. Both bounds are included, as SQL
 * says, so the planner makes it one Qdrant range from the low bound to the high one.
 */
public record Between(Expression column, Expression low, Expression high, boolean negated)
    implements Expression {}
