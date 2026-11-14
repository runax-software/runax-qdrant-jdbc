package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ColumnSpec;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;

/**
 * Reads {@code CREATE TABLE [IF NOT EXISTS] docs (column, ...)}, each column through
 * {@link ColumnParser}, and hands {@code CREATE INDEX} and {@code CREATE [OR REPLACE] VIEW} to
 * their own parsers.
 */
public final class CreateParser {

    private final TokenStream tokenStream;

    public CreateParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("CREATE");

        if (this.tokenStream.peekWord("INDEX")) {
            return new IndexParser(this.tokenStream).parseCreate();
        }
        if (this.tokenStream.peekWord("VIEW")) {
            return new ViewParser(this.tokenStream).parseCreate(false);
        }
        if (this.tokenStream.matchKeyword("OR")) {
            expectWord("REPLACE");
            return new ViewParser(this.tokenStream).parseCreate(true);
        }
        if (!this.tokenStream.peekKeyword("TABLE")) {
            throw this.tokenStream.error("Expected TABLE, INDEX, VIEW or OR REPLACE VIEW");
        }

        this.tokenStream.expectKeyword("TABLE");

        boolean ifNotExists = this.tokenStream.peekWord("IF");
        if (ifNotExists) {
            this.tokenStream.advance();
            this.tokenStream.expectKeyword("NOT");
            expectWord("EXISTS");
        }

        var table = new TableParser(this.tokenStream).parse();
        var columns = new ArrayList<ColumnSpec>();

        this.tokenStream.expectSymbol("(");
        do {
            columns.add(new ColumnParser(this.tokenStream).parse());
        } while (this.tokenStream.matchSymbol(","));
        this.tokenStream.expectSymbol(")");

        return new CreateTableStatement(table, ifNotExists, columns);
    }

    private boolean matchWord(String word) {
        if (!this.tokenStream.peekWord(word)) {
            return false;
        }

        this.tokenStream.advance();
        return true;
    }

    private void expectWord(String word) throws SQLSyntaxErrorException {
        if (!matchWord(word)) {
            throw this.tokenStream.error("Expected " + word);
        }
    }
}
