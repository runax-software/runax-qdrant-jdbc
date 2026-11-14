package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

/**
 * An ALTER TABLE ... ADD COLUMN: a named vector added to a collection that exists.
 *
 * @param table       the collection to change
 * @param column      the column, as a CREATE TABLE would declare it
 * @param ifNotExists whether a vector already there under the name is fine rather than an error
 */
public record AddColumnStatement(TableName table, ColumnSpec column, boolean ifNotExists)
    implements Statement {}
