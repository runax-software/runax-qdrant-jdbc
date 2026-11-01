package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

/**
 * A table named in FROM, with however much of {@code catalog.schema.table} the statement
 * spelled out. {@code catalog} and {@code schema} are null when it left them out, as
 * {@code FROM docs} does; the planner decides what an unqualified name means.
 *
 * @param catalog the catalog part, or {@code null}
 * @param schema  the schema part, or {@code null}
 * @param name    the table name, always present
 */
public record TableName(String catalog, String schema, String name) {

    /**
     * An unqualified name, such as a collection read as {@code FROM docs}.
     */
    public static TableName of(String name) {
        return new TableName(null, null, name);
    }

    /**
     * A name in a schema, such as {@code FROM system.collections}.
     */
    public static TableName of(String schema, String name) {
        return new TableName(null, schema, name);
    }

    /**
     * The name as it was written, for error messages.
     */
    public String qualified() {
        var text = new StringBuilder();

        if (catalog != null) text.append(catalog).append('.');
        if (schema != null) text.append(schema).append('.');

        return text.append(name).toString();
    }
}
