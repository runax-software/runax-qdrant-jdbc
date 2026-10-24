package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

/** A condition or value in a WHERE clause. */
public sealed interface Expression
    permits Column, Literal, Parameter, Comparison, And, Or, Not, InList, IsNull {}
