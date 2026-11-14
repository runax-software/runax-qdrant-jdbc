package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ColumnSpec;

import java.sql.SQLSyntaxErrorException;
import java.util.Locale;

/**
 * Reads one column as a CREATE TABLE or an ALTER TABLE ... ADD COLUMN declares it:
 * {@code name type [(size)] [DISTANCE d] [MODIFIER m] [INDEX]}. The type and the words after it
 * mean something only here, so they're read as words and stay usable as names everywhere else.
 */
public final class ColumnParser {

    private final TokenStream tokenStream;

    public ColumnParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public ColumnSpec parse() throws SQLSyntaxErrorException {
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
            if (this.tokenStream.matchWord("DISTANCE")) {
                distance = word("Expected a distance, such as COSINE");
            } else if (this.tokenStream.matchWord("MODIFIER")) {
                modifier = word("Expected a modifier, such as IDF");
            } else if (this.tokenStream.matchWord("INDEX")) {
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
}
