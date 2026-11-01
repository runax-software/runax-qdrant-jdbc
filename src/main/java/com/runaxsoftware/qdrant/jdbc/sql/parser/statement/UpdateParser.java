package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ExpressionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Assignment;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.UpdateStatement;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;

/**
 * Reads {@code UPDATE docs [AS t] SET column = value [, ...] [WHERE ...]}, as DataGrip's data
 * editor writes it when a cell is saved. Whether an UPDATE without WHERE may run is the
 * planner's to say.
 */
public final class UpdateParser {

    private final TokenStream tokenStream;
    private final ExpressionParser expressionParser;

    public UpdateParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
        this.expressionParser = new ExpressionParser(tokenStream);
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("UPDATE");

        TableName table = new TableParser(this.tokenStream).parse();
        String alias = parseAlias();

        this.tokenStream.expectKeyword("SET");
        var assignments = new ArrayList<Assignment>();

        do {
            var column = this.expressionParser.parseColumn();
            this.tokenStream.expectSymbol("=");
            assignments.add(new Assignment(column, this.expressionParser.parseValue()));
        } while (this.tokenStream.matchSymbol(","));

        Expression where = this.tokenStream.matchKeyword("WHERE")
            ? this.expressionParser.parseOr()
            : null;

        return new UpdateStatement(table, alias, assignments, where);
    }

    // A name for the collection, with or without AS; SET is a keyword, so it isn't taken for one
    private String parseAlias() throws SQLSyntaxErrorException {
        if (this.tokenStream.matchKeyword("AS")) {
            return this.tokenStream.expectIdentifier();
        }

        return this.tokenStream.peekIdentifier() ? this.tokenStream.expectIdentifier() : null;
    }
}
