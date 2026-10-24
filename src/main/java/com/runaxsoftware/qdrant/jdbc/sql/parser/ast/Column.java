package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

/** A column name. */
public record Column(String name) implements Expression {}
