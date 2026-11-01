package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ColumnSpec;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Reads {@code CREATE TABLE [IF NOT EXISTS] docs (column type [(size)] [DISTANCE d]
 * [MODIFIER m] [INDEX], ...)}. The type and the words after it mean something only here, so
 * they're read as words and stay usable as names everywhere else.
 */
public final class CreateParser {

    private final TokenStream tokenStream;

    public CreateParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("CREATE");
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
            columns.add(parseColumn());
        } while (this.tokenStream.matchSymbol(","));
        this.tokenStream.expectSymbol(")");

        return new CreateTableStatement(table, ifNotExists, columns);
    }

    private ColumnSpec parseColumn() throws SQLSyntaxErrorException {
        String name = this.tokenStream.expectIdentifier();
        String type = word("Expected a column type, such as VECTOR(384) or KEYWORD INDEX");

        Integer size = null;
        if (this.tokenStream.matchSymbol("(")) {
            size = Math.toIntExact(this.tokenStream.expectWholeNumber());
            this.tokenStream.expectSymbol(")");
        }

        String distance = null;
        String modifier = null;
        boolean index = false;

        while (true) {
            if (matchWord("DISTANCE")) {
                distance = word("Expected a distance, such as COSINE");
            } else if (matchWord("MODIFIER")) {
                modifier = word("Expected a modifier, such as IDF");
            } else if (matchWord("INDEX")) {
                index = true;
            } else {
                break;
            }
        }

        return new ColumnSpec(name, type, size, distance, modifier, index);
    }

    // The next word, upper case
    private String word(String expected) throws SQLSyntaxErrorException {
        if (!this.tokenStream.peekIdentifier()) {
            throw this.tokenStream.error(expected);
        }

        return this.tokenStream.advance().text().toUpperCase(Locale.ROOT);
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
