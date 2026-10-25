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
        var items = new ArrayList<SelectItem>();

        if (!this.tokenStream.matchSymbol("*")) {
            do {
                items.add(parseItem());
            } while (this.tokenStream.matchSymbol(","));
        }

        // FROM is optional: SELECT 'keep alive' reads no collection
        String table = null;
        if (this.tokenStream.matchKeyword("FROM")) {
            table = this.tokenStream.expectIdentifier();
        } else if (this.tokenStream.peekIdentifier()) {
            // "SELECT name docs" is a missing FROM; aliases need AS
            throw this.tokenStream.error("Expected FROM");
        }

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
        return new SelectStatement(items, table, where, orderBy, limit, offset);
    }

    private SelectItem parseItem() throws SQLSyntaxErrorException {
        Expression expression = this.tokenStream.peekIdentifier()
            ? new Column(this.tokenStream.expectIdentifier())
            : this.expressionParser.parseValue();
        String alias = this.tokenStream.matchKeyword("AS") ? this.tokenStream.expectIdentifier() : null;

        return new SelectItem(expression, alias);
    }
}
