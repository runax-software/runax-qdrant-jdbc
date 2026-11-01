package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

/**
 * A DROP TABLE: a collection deleted, with its points and indexes.
 *
 * @param table    the collection to delete
 * @param ifExists whether a missing collection is fine rather than an error
 */
public record DropTableStatement(TableName table, boolean ifExists) implements Statement {}
