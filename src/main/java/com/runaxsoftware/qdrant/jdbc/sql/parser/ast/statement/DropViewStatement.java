package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

/**
 * A DROP VIEW: an alias deleted, the collection it points at kept.
 *
 * @param view     the view's name
 * @param ifExists whether a missing view is fine rather than an error
 */
public record DropViewStatement(TableName view, boolean ifExists) implements Statement {}
