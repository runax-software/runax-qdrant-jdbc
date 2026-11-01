package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.TruncateStatement;

import java.sql.SQLSyntaxErrorException;

/**
 * Reads {@code TRUNCATE [TABLE] docs}.
 */
public final class TruncateParser {

    private final TokenStream tokenStream;

    public TruncateParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("TRUNCATE");
        this.tokenStream.matchKeyword("TABLE");

        return new TruncateStatement(new TableParser(this.tokenStream).parse());
    }
}
