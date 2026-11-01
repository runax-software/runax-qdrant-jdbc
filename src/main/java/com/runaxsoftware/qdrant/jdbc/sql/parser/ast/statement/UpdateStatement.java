package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

import java.util.List;

/**
 * An UPDATE of the points a condition matches.
 *
 * @param table       the collection whose points change
 * @param alias       the name the statement gives that collection, or {@code null}
 * @param assignments the SET list, in order
 * @param where       the WHERE condition, or {@code null}
 */
public record UpdateStatement(TableName table, String alias, List<Assignment> assignments,
                              Expression where) implements Statement {

    public UpdateStatement {
        assignments = List.copyOf(assignments);
    }
}
