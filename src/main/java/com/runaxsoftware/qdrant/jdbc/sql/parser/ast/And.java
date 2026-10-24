package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

/** left AND right. */
public record And(Expression left, Expression right) implements Expression {}
