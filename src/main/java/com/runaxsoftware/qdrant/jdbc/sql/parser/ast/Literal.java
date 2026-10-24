package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

/** A constant: a String, Long, Double or Boolean. */
public record Literal(Object value) implements Expression {}
