package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;

/**
 * A column name, with the table or alias it was named under, as in {@code t.name}, or
 * {@code null} when it was named on its own. Which qualifiers a statement may use is the
 * planner's to say.
 *
 * @param qualifier the table or alias before the dot, or {@code null}
 * @param name      the column name
 */
public record Column(String qualifier, String name) implements Expression {

    /**
     * A column named on its own, such as {@code name}.
     */
    public static Column of(String name) {
        return new Column(null, name);
    }

    /**
     * The column as the statement wrote it, for error messages.
     */
    public String qualified() {
        return qualifier == null ? name : qualifier + "." + name;
    }
}
