package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ExpressionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.InsertStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code INSERT INTO docs (column, ...) VALUES (value, ...) [, (value, ...)]}. The column
 * list is required: a collection's columns come from its points, so their order isn't one a
 * statement could rely on. A value may be a constant, {@code NULL}, a placeholder, a vector
 * written out, or a call such as {@code embed('text')}.
 */
public final class InsertParser {

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

        return new InsertStatement(table, columns, rows);
    }
}
