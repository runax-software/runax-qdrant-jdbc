package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

/** left OR right. */
public record Or(Expression left, Expression right) implements Expression {}
