package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;

/**
 * Reads {@code DROP TABLE [IF EXISTS] docs}. IF and EXISTS are words only here, so a collection
 * may still be called {@code if}: they're read as the clause only when both are there.
 */
public final class DropParser {

    private final TokenStream tokenStream;

    public DropParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("DROP");
        this.tokenStream.expectKeyword("TABLE");

        boolean ifExists = this.tokenStream.peekWord("IF")
            && this.tokenStream.peekWordAfterNext("EXISTS");
        if (ifExists) {
            this.tokenStream.advance();
            this.tokenStream.advance();
        }

        return new DropTableStatement(new TableParser(this.tokenStream).parse(), ifExists);
    }
}
