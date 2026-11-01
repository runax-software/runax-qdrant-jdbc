package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;

import java.util.List;

/**
 * {@code SET qdrant.consistency = 'majority'}: an option for the connection's requests from
 * now on. Which options exist is the planner's to say.
 *
 * @param name  the option's name, part by part, as {@code [qdrant, consistency]}
 * @param value the value, or {@code null} for {@code DEFAULT}, which resets it
 */
public record SetStatement(List<String> name, Expression value) implements Statement {

    public SetStatement {
        name = List.copyOf(name);
    }
}
