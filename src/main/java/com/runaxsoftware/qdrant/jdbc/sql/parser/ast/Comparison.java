package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

/** left operator right, where operator is =, <>, !=, <, <=, > or >=. */
public record Comparison(Expression left, String operator, Expression right)
    implements Expression {}
