package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;

/** left OR right. */
public record Or(Expression left, Expression right) implements Expression {}
