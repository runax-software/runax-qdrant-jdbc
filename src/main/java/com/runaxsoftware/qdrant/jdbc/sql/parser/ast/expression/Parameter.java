package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;

/** A ? placeholder: the n-th one in the statement, counting from 1. */
public record Parameter(int index) implements Expression {}
