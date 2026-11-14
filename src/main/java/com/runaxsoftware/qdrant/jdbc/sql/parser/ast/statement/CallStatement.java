package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import java.util.List;

/**
 * A CALL: one of the driver's procedures, with its arguments.
 *
 * @param qualifier the names before the procedure's, as written, such as {@code qdrant}; empty
 *                  when it's called by its name alone
 * @param procedure the procedure's name, as written
 * @param arguments the arguments, in order
 */
public record CallStatement(List<String> qualifier, String procedure,
                            List<CallArgument> arguments) implements Statement {

    public CallStatement {
        qualifier = List.copyOf(qualifier);
        arguments = List.copyOf(arguments);
    }
}
