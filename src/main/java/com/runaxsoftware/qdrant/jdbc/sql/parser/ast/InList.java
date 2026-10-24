package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

import java.util.List;

/** column IN (values), or NOT IN when negated. */
public record InList(Expression column, List<Expression> values, boolean negated)
    implements Expression {}
