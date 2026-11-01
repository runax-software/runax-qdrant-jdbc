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
    private final TableParser tableParser;

    public SelectParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
        this.expressionParser = new ExpressionParser(tokenStream);
        this.tableParser = new TableParser(tokenStream);
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("SELECT");
        var items = new ArrayList<SelectItem>();

        do {
            items.add(parseItem());
        } while (this.tokenStream.matchSymbol(","));

        // FROM is optional: SELECT 'keep alive' reads no collection
        TableName table = null;
        String alias = null;
        if (this.tokenStream.matchKeyword("FROM")) {
            table = this.tableParser.parse();
            alias = parseAlias();
        } else if (this.tokenStream.peekIdentifier()) {
            // "SELECT name docs" is a missing FROM; aliases need AS
            throw this.tokenStream.error("Expected FROM");
        }

        Expression where = this.tokenStream.matchKeyword("WHERE") ? this.expressionParser.parseOr() : null;

        var orderBy = new ArrayList<OrderItem>();
        if (this.tokenStream.matchKeyword("ORDER")) {
            this.tokenStream.expectKeyword("BY");

            do {
                Expression term = this.expressionParser.parseTerm();
                boolean descending = this.tokenStream.matchKeyword("DESC");

                if (!descending) {
                    this.tokenStream.matchKeyword("ASC");
                }

                orderBy.add(new OrderItem(term, descending));
            } while(this.tokenStream.matchSymbol(","));
        }

        Long limit = this.tokenStream.matchKeyword("LIMIT") ? this.tokenStream.expectWholeNumber() : null;
        Long offset = this.tokenStream.matchKeyword("OFFSET") ? this.tokenStream.expectWholeNumber() : null;
        return new SelectStatement(items, table, alias, where, orderBy, limit, offset);
    }

    /**
     * One entry of the select list: {@code *}, {@code t.*}, a column named on its own or under
     * the table or an alias, or a constant, each with its own alias or none. A star takes no
     * alias of its own, as it stands for several columns.
     */
    private SelectItem parseItem() throws SQLSyntaxErrorException {
        if (this.tokenStream.matchSymbol("*")) {
            return new SelectItem(Star.of(), null);
        }

        if (!this.tokenStream.peekIdentifier()) {
            return item(this.expressionParser.parseValue());
        }

        String name = this.tokenStream.expectIdentifier();
        if (!this.tokenStream.matchSymbol(".")) {
            return item(Column.of(name));
        }

        return this.tokenStream.matchSymbol("*")
            ? new SelectItem(new Star(name), null)
            : item(new Column(name, this.tokenStream.expectIdentifier()));
    }

    private SelectItem item(Expression expression) throws SQLSyntaxErrorException {
        String alias = this.tokenStream.matchKeyword("AS") ? this.tokenStream.expectIdentifier() : null;

        return new SelectItem(expression, alias);
    }

    /**
     * The name a statement gives the table it reads, with or without AS, as tools write when
     * they qualify the columns they select. Keywords such as WHERE and LIMIT aren't names, so
     * a statement without an alias reads the same as before.
     */
    private String parseAlias() throws SQLSyntaxErrorException {
        if (this.tokenStream.matchKeyword("AS")) {
            return this.tokenStream.expectIdentifier();
        }

        return this.tokenStream.peekIdentifier() ? this.tokenStream.expectIdentifier() : null;
    }
}
