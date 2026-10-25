package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

/** One column of an ORDER BY. */
public record OrderItem(String column, boolean descending) {}
