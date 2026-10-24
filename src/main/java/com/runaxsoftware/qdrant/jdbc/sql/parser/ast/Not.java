package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

/** NOT operand. */
public record Not(Expression operand) implements Expression {}
