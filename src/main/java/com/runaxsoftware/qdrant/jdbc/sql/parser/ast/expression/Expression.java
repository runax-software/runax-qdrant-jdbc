package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.*;

/** A condition or value in a WHERE clause, or an entry of a select list. */
public sealed interface Expression
    permits Column, Star, Literal, Parameter, Comparison, And, Or, Not, InList, IsNull {}
