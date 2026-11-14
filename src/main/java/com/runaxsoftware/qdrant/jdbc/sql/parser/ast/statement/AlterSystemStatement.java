package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An ALTER SYSTEM SET (...): settings of the server itself, rather than of a collection.
 *
 * @param options the settings, by name as written, in order
 */
public record AlterSystemStatement(Map<String, Expression> options) implements Statement {

    public AlterSystemStatement {
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }
}
