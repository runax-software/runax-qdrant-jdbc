package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import java.util.List;

/**
 * {@code RESET qdrant.consistency}, or {@code RESET ALL}: options back to Qdrant's defaults.
 *
 * @param name the option's name, part by part, or {@code null} for every option
 */
public record ResetStatement(List<String> name) implements Statement {

    public ResetStatement {
        name = name == null ? null : List.copyOf(name);
    }
}
