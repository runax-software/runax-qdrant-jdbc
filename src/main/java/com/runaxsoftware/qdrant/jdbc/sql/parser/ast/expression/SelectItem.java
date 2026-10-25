package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

/** One entry of a SELECT list: a column or a constant, with its alias or null. */
public record SelectItem(Expression expression, String alias) {}
