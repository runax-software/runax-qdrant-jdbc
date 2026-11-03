package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

/**
 * column LIKE pattern, or NOT LIKE when negated. Which of Qdrant's matches a pattern is, the
 * planner says: Qdrant matches a prefix and a substring, and nothing else a pattern could ask
 * for.
 */
public record Like(Expression column, Expression pattern, boolean negated) implements Expression {}
