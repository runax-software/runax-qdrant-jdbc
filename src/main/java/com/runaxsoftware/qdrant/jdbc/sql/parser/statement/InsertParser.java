package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ExpressionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.InsertStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.OnConflict;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code INSERT INTO docs (column, ...) VALUES (value, ...) [, (value, ...)]}. The column
 * list is required: a collection's columns come from its points, so their order isn't one a
 * statement could rely on. A value may be a constant, {@code NULL}, a placeholder, a vector
 * written out, or a call such as {@code embed('text')}.
 *
 * <p>An {@code ON CONFLICT [(_id)] DO NOTHING} or {@code DO UPDATE [WHERE ...]} clause may
 * follow, saying what to do with a row whose point ID the collection already has. Without one a
 * taken ID is an error, as a primary key's is.
 */
public final class InsertParser {

    /** The only column two Qdrant points can collide on. */
    private static final String CONFLICT_TARGET = "_id";

    private final TokenStream tokenStream;
    private final ExpressionParser expressionParser;

    public InsertParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
        this.expressionParser = new ExpressionParser(tokenStream);
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("INSERT");
        this.tokenStream.expectKeyword("INTO");

        TableName table = new TableParser(this.tokenStream).parse();

        if (!this.tokenStream.matchSymbol("(")) {
            throw this.tokenStream.error("Expected the columns in brackets, as INSERT INTO docs "
                + "(_id, lang) VALUES (...)");
        }

        var columns = new ArrayList<Column>();
        do {
            columns.add(this.expressionParser.parseColumn());
        } while (this.tokenStream.matchSymbol(","));
        this.tokenStream.expectSymbol(")");

        this.tokenStream.expectKeyword("VALUES");
        var rows = new ArrayList<List<Expression>>();

        do {
            this.tokenStream.expectSymbol("(");
            var row = new ArrayList<Expression>();

            do {
                row.add(this.expressionParser.parseValueOrCall());
            } while (this.tokenStream.matchSymbol(","));

            this.tokenStream.expectSymbol(")");
            rows.add(row);
        } while (this.tokenStream.matchSymbol(","));

        return new InsertStatement(table, columns, rows, parseOnConflict());
    }

    /**
     * {@code ON CONFLICT [(_id)] DO NOTHING}, or {@code DO UPDATE [WHERE ...]}. None of ON,
     * CONFLICT, DO and NOTHING is a keyword, so each is read where it can appear and all four
     * stay usable as names. The target in brackets may only be {@code _id}, which the planner
     * says: a Qdrant point has one key.
     */
    private OnConflict parseOnConflict() throws SQLSyntaxErrorException {
        if (!this.tokenStream.matchWord("ON")) {
            return OnConflict.FAIL;
        }
        if (!this.tokenStream.matchWord("CONFLICT")) {
            throw this.tokenStream.error("Expected CONFLICT after ON, as "
                + "ON CONFLICT (_id) DO NOTHING");
        }

        Column target = null;
        if (this.tokenStream.matchSymbol("(")) {
            target = this.expressionParser.parseColumn();
            this.tokenStream.expectSymbol(")");
        }

        if (!this.tokenStream.matchWord("DO")) {
            throw this.tokenStream.error("Expected DO after ON CONFLICT, as "
                + "ON CONFLICT (_id) DO NOTHING");
        }

        if (this.tokenStream.matchWord("NOTHING")) {
            return conflict(OnConflict.Action.NOTHING, target, null);
        }
        if (!this.tokenStream.matchKeyword("UPDATE")) {
            throw this.tokenStream.error("Expected NOTHING or UPDATE after DO");
        }

        // Qdrant replaces a whole point, so there's no column list to set: the row is the point
        if (this.tokenStream.peekKeyword("SET")) {
            throw this.tokenStream.errorAt("DO UPDATE takes no SET: Qdrant replaces the whole "
                + "point with the row the statement gives, so write ON CONFLICT (_id) DO UPDATE "
                + "and give every column the point should have", "0A000");
        }

        Expression where = this.tokenStream.matchKeyword("WHERE")
            ? this.expressionParser.parseOr()
            : null;

        return conflict(OnConflict.Action.UPDATE, target, where);
    }

    /**
     * The clause, once the target has been checked. Qdrant's only key is the point ID, so a
     * target naming anything else can't be what the statement meant.
     */
    private OnConflict conflict(OnConflict.Action action, Column target, Expression where)
        throws SQLSyntaxErrorException {
        if (target != null && !target.name().equals(CONFLICT_TARGET)) {
            throw this.tokenStream.errorAt("ON CONFLICT takes " + CONFLICT_TARGET + ", and '"
                + target.name() + "' isn't it: a Qdrant point has one key, its ID, so that's the "
                + "only column two points can collide on", "42000");
        }

        return new OnConflict(action, where);
    }
}
