package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

/**
 * An ALTER TABLE ... DROP COLUMN: a named vector deleted from a collection, with every point's
 * value for it.
 *
 * @param table    the collection to change
 * @param column   the column to drop
 * @param ifExists whether a missing vector is fine rather than an error
 */
public record DropColumnStatement(TableName table, String column, boolean ifExists)
    implements Statement {}
