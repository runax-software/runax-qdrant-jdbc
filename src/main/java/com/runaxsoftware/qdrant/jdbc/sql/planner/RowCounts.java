package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;

import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * The whole numbers a LIMIT, an OFFSET or a {@code LIMIT n BY column} take. The parser reads a
 * number written out and refuses anything else, so only the value a prepared statement gives for
 * a {@code ?} can be negative, null, or not a number; it's bound before the statement is planned,
 * so every plan sees the number and nothing is decided before it's known.
 */
final class RowCounts {

    private RowCounts() {
    }

    /**
     * The number a clause says, or {@code null} when the statement has no such clause.
     *
     * @param clause the clause, for the error, as {@code LIMIT}
     */
    static Long of(Expression count, String clause) throws SQLException {
        if (count == null) {
            return null;
        }
        if (count instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("Parameters are given through a "
                + "PreparedStatement: Connection.prepareStatement(sql), then setInt and the "
                + "other setters");
        }
        if (count instanceof Literal literal && literal.value() instanceof Long number && number >= 0) {
            return number;
        }

        // The error a LIMIT written as -1 or 'ten' gets from the parser, as one about the value
        throw new SQLDataException(clause + " takes a whole number of 0 or more, not "
            + describe(count), "22023");
    }

    private static String describe(Expression count) {
        if (!(count instanceof Literal literal)) {
            return "a vector";
        }

        Object value = literal.value();
        return value instanceof String text ? "'" + text + "'" : String.valueOf(value);
    }
}
