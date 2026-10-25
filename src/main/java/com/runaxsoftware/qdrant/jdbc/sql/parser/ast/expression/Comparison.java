package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;

/** left operator right, where operator is =, <>, !=, <, <=, > or >=. */
public record Comparison(Expression left, String operator, Expression right)
    implements Expression {}
