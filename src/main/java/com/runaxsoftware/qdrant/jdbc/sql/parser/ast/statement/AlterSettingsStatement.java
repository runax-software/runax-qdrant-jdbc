package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An ALTER TABLE ... SET (...) or ALTER TABLE ... ALTER COLUMN ... SET (...): settings of a
 * collection, or of one of its vectors, changed in place.
 *
 * @param table   the collection to change
 * @param column  the vector column whose settings these are, or {@code null} for the
 *                collection's own
 * @param options the settings, by name as written, dots and case kept, in order
 */
public record AlterSettingsStatement(TableName table, String column,
                                     Map<String, Expression> options) implements Statement {

    public AlterSettingsStatement {
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }
}
