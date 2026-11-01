package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

/**
 * A DROP INDEX, named as {@code DROP INDEX lang_idx ON docs} or by its field, as
 * {@code DROP INDEX ON docs (lang)}.
 *
 * @param table    the collection
 * @param name     the index's name, or {@code null} when the field is given instead
 * @param column   the indexed field, or {@code null} when the name is given instead
 * @param ifExists whether a field without an index is fine rather than an error
 */
public record DropIndexStatement(TableName table, String name, Column column, boolean ifExists)
    implements Statement {}
