package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

import java.util.List;
import java.util.Locale;

/**
 * A call such as {@code similarity(dense, [0.1, 0.2])}: SQL has no word for what Qdrant does,
 * so the driver's own operations are functions. Which calls exist, and what they accept, is
 * the planner's to say.
 *
 * @param name      the function name, as the statement wrote it
 * @param arguments the arguments, in order
 */
public record FunctionCall(String name, List<Expression> arguments) implements Expression {

    public FunctionCall(String name, List<Expression> arguments) {
        this.name = name;
        this.arguments = List.copyOf(arguments);
    }

    /**
     * Whether this is a call of the given function, whatever case the statement wrote: the
     * driver names these itself, as it does its catalog and schemas.
     */
    public boolean is(String function) {
        return name.toLowerCase(Locale.ROOT).equals(function);
    }
}
