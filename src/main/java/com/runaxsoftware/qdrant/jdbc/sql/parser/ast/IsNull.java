package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

/** column IS NULL, or IS NOT NULL when negated. */
public record IsNull(Expression column, boolean negated) implements Expression {}
