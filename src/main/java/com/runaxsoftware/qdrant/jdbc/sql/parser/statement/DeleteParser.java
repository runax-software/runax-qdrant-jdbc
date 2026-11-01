package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ExpressionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DeleteStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;

/**
 * Reads {@code DELETE FROM docs [AS t] [WHERE ...]}. Whether a DELETE without WHERE may run is
 * the planner's to say.
 */
public final class DeleteParser {

    private final TokenStream tokenStream;

    public DeleteParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("DELETE");
        this.tokenStream.expectKeyword("FROM");

        TableName table = new TableParser(this.tokenStream).parse();
        String alias = parseAlias();
        Expression where = this.tokenStream.matchKeyword("WHERE")
            ? new ExpressionParser(this.tokenStream).parseOr()
            : null;

        return new DeleteStatement(table, alias, where);
    }

    // A name for the collection, with or without AS, as in a SELECT
    private String parseAlias() throws SQLSyntaxErrorException {
        if (this.tokenStream.matchKeyword("AS")) {
            return this.tokenStream.expectIdentifier();
        }

        return this.tokenStream.peekIdentifier() ? this.tokenStream.expectIdentifier() : null;
    }
}
