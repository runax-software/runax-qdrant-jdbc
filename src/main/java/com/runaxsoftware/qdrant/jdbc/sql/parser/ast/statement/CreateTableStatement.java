package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

import java.util.List;

/**
 * A CREATE TABLE: a collection with its vectors and payload indexes.
 *
 * @param table       the collection to create
 * @param ifNotExists whether an existing collection is fine rather than an error
 * @param columns     the columns, in order
 */
public record CreateTableStatement(TableName table, boolean ifNotExists, List<ColumnSpec> columns)
    implements Statement {

    public CreateTableStatement {
        columns = List.copyOf(columns);
    }
}
