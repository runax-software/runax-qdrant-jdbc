package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

/** A ? placeholder: the n-th one in the statement, counting from 1. */
public record Parameter(int index) implements Expression {}
