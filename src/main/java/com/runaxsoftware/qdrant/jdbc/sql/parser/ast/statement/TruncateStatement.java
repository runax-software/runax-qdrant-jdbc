package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

/**
 * A TRUNCATE TABLE: every point of a collection deleted, the collection and its indexes kept.
 *
 * @param table the collection to empty
 */
public record TruncateStatement(TableName table) implements Statement {}
