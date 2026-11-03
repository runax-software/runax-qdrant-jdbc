package com.runaxsoftware.qdrant.jdbc.sql.parser;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.And;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Comparison;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.FunctionCall;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.InList;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.IsNull;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Like;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Not;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Or;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.OrderItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Assignment;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DeleteStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.InsertStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.UpdateStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The {@code ?} placeholders of a parsed statement: how many there are, and the statement with
 * values in their place. A prepared statement parses its SQL once and binds new values for every
 * run, so a value never passes through the SQL text and can't change what the statement means.
 */
public final class Parameters {

    private Parameters() {
    }

    /**
     * How many placeholders the statement has; they're numbered from 1 in the order they appear.
     */
    public static int count(Statement statement) {
        int[] highest = {0};

        bind(statement, index -> {
            highest[0] = Math.max(highest[0], index);
            return new Parameter(index);
        });

        return highest[0];
    }

    /**
     * The statement with each placeholder replaced by the value for its number.
     *
     * @param values the expression for the n-th placeholder, given n
     */
    public static Statement bind(Statement statement, IntFunction<Expression> values) {
        if (statement instanceof SelectStatement select) {
            var items = new ArrayList<SelectItem>();
            for (SelectItem item : select.items()) {
                items.add(new SelectItem(bind(item.expression(), values), item.alias()));
            }

            var orderBy = new ArrayList<OrderItem>();
            for (OrderItem item : select.orderBy()) {
                orderBy.add(new OrderItem(bind(item.expression(), values), item.descending()));
            }

            // GROUP BY names columns only, so it holds no placeholder
            return new SelectStatement(items, select.table(), select.alias(),
                bind(select.where(), values), select.groupBy(), orderBy, select.limit(),
                select.offset());
        }
        if (statement instanceof InsertStatement insert) {
            var rows = new ArrayList<List<Expression>>();
            for (List<Expression> row : insert.rows()) rows.add(bind(row, values));

            return new InsertStatement(insert.table(), insert.columns(), rows);
        }
        if (statement instanceof UpdateStatement update) {
            var assignments = new ArrayList<Assignment>();
            for (Assignment assignment : update.assignments()) {
                assignments.add(new Assignment(assignment.column(), bind(assignment.value(), values)));
            }

            return new UpdateStatement(update.table(), update.alias(), assignments,
                bind(update.where(), values));
        }
        if (statement instanceof DeleteStatement delete) {
            return new DeleteStatement(delete.table(), delete.alias(), bind(delete.where(), values));
        }

        // The other statements take no values
        return statement;
    }

    private static Expression bind(Expression expression, IntFunction<Expression> values) {
        if (expression instanceof Parameter parameter) {
            return values.apply(parameter.index());
        }
        if (expression instanceof Comparison comparison) {
            return new Comparison(bind(comparison.left(), values), comparison.operator(),
                bind(comparison.right(), values));
        }
        if (expression instanceof And and) {
            return new And(bind(and.left(), values), bind(and.right(), values));
        }
        if (expression instanceof Or or) {
            return new Or(bind(or.left(), values), bind(or.right(), values));
        }
        if (expression instanceof Not not) {
            return new Not(bind(not.operand(), values));
        }
        if (expression instanceof InList in) {
            return new InList(bind(in.column(), values), bind(in.values(), values), in.negated());
        }
        if (expression instanceof IsNull isNull) {
            return new IsNull(bind(isNull.column(), values), isNull.negated());
        }
        if (expression instanceof Like like) {
            return new Like(bind(like.column(), values), bind(like.pattern(), values),
                like.negated());
        }
        if (expression instanceof FunctionCall call) {
            return new FunctionCall(call.name(), bind(call.arguments(), values));
        }

        // Columns, stars, and constants hold no placeholder; neither does a missing WHERE
        return expression;
    }

    private static List<Expression> bind(List<Expression> expressions, IntFunction<Expression> values) {
        var bound = new ArrayList<Expression>(expressions.size());
        for (Expression expression : expressions) bound.add(bind(expression, values));

        return bound;
    }
}
