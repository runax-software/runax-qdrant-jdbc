package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

/**
 * A {@code *} in the select list: every column of the table it reads, named under the table or
 * an alias, as in {@code t.*}, or on its own.
 *
 * @param qualifier the table or alias before the dot, or {@code null} for a plain {@code *}
 */
public record Star(String qualifier) implements Expression {

    /**
     * A plain {@code *}.
     */
    public static Star of() {
        return new Star(null);
    }

    /**
     * The star as the statement wrote it, for error messages.
     */
    public String qualified() {
        return qualifier == null ? "*" : qualifier + ".*";
    }
}
