package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ExpressionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.*;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;

public final class SelectParser {

    private final TokenStream tokenStream;
    private final ExpressionParser expressionParser;

    public SelectParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
        this.expressionParser = new ExpressionParser(tokenStream);
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("SELECT");
        var columns = new ArrayList<String>();

        if (!this.tokenStream.matchSymbol("*")) {
            do {
                columns.add(this.tokenStream.expectIdentifier());
            } while (this.tokenStream.matchSymbol(","));
        }

        this.tokenStream.expectKeyword("FROM");
        String table = this.tokenStream.expectIdentifier();

        Expression where = this.tokenStream.matchKeyword("WHERE") ? this.expressionParser.parseOr() : null;

        var orderBy = new ArrayList<OrderItem>();
        if (this.tokenStream.matchKeyword("ORDER")) {
            this.tokenStream.expectKeyword("BY");

            do {
                String column = this.tokenStream.expectIdentifier();
                boolean descending = this.tokenStream.matchKeyword("DESC");

                if (!descending) {
                    this.tokenStream.matchKeyword("ASC");
                }

                orderBy.add(new OrderItem(column, descending));
            } while(this.tokenStream.matchSymbol(","));
        }

        Long limit = this.tokenStream.matchKeyword("LIMIT") ? this.tokenStream.expectWholeNumber() : null;
        Long offset = this.tokenStream.matchKeyword("OFFSET") ? this.tokenStream.expectWholeNumber() : null;
        return new SelectStatement(columns, table, where, orderBy, limit, offset);
    }
}
