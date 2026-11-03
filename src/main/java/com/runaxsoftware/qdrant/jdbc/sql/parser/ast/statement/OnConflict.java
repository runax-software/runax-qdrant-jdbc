package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;

/**
 * What an INSERT does with a row whose point ID the collection already has, as
 * {@code ON CONFLICT (_id) DO NOTHING} or {@code DO UPDATE [WHERE ...]} says. A statement with
 * no such clause is {@link #FAIL}, which is what a primary key does.
 *
 * @param action what to do with a point that's already there
 * @param where  the condition an existing point must meet to be replaced, or {@code null} for
 *               all of them; only a DO UPDATE has one
 */
public record OnConflict(Action action, Expression where) {

    /**
     * The three answers to "this ID is taken".
     */
    public enum Action {
        /** Fail, as inserting a duplicate primary key does. */
        FAIL,
        /** Leave the point as it is. */
        NOTHING,
        /** Replace it with the row. */
        UPDATE
    }

    /**
     * No ON CONFLICT clause: a taken ID is an error.
     */
    public static final OnConflict FAIL = new OnConflict(Action.FAIL, null);

    /**
     * Whether the statement said what to do, rather than leaving a taken ID an error.
     */
    public boolean given() {
        return action != Action.FAIL;
    }
}
