package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;

/** A constant: a String, Long, Double or Boolean, or {@code null} for NULL. */
public record Literal(Object value) implements Expression {}
