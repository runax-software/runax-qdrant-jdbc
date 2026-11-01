package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import java.util.List;

/**
 * {@code SHOW qdrant.consistency}, or {@code SHOW ALL}: what the connection's options are.
 *
 * @param name the option's name, part by part, or {@code null} for every option
 */
public record ShowStatement(List<String> name) implements Statement {

    public ShowStatement {
        name = name == null ? null : List.copyOf(name);
    }
}
