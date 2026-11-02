package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A CREATE INDEX: a payload index on one field of a collection.
 *
 * @param table       the collection
 * @param column      the field to index
 * @param name        the name the statement gives the index, or {@code null}; Qdrant names an
 *                    index by its field, so this is checked rather than kept
 * @param using       the index type after USING, upper case, or {@code null} for the column's
 * @param options     the WITH options, by name, in order
 * @param ifNotExists whether an index already on the field is fine rather than an error
 */
public record CreateIndexStatement(TableName table, Column column, String name, String using,
                                   Map<String, Expression> options, boolean ifNotExists)
    implements Statement {

    public CreateIndexStatement {
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }
}
