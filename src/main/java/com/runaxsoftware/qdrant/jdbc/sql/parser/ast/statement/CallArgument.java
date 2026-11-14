package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;

/**
 * One argument of a CALL.
 *
 * @param name  the name before {@code =>}, as written, or {@code null} for an argument given
 *              by its position
 * @param value the value
 */
public record CallArgument(String name, Expression value) {}
