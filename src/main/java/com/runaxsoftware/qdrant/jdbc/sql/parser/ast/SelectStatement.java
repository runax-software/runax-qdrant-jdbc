package com.runaxsoftware.qdrant.jdbc.sql.parser.ast;

import java.util.List;

/** A SELECT. columns is empty for SELECT *; where, limit and offset are null when absent. */
public record SelectStatement(
    List<String> columns,
    String table,
    Expression where,
    List<OrderItem> orderBy,
    Long limit,
    Long offset
) implements Statement {}
