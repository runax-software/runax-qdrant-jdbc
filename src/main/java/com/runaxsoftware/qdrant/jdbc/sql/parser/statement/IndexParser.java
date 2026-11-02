package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ExpressionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateIndexStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropIndexStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Reads the rest of {@code CREATE INDEX [IF NOT EXISTS] [name] ON docs (column) [USING type]
 * [WITH (option = value, ...)]} and {@code DROP INDEX [IF EXISTS] name ON docs}, or
 * {@code DROP INDEX [IF EXISTS] ON docs (column)}, once CREATE or DROP is read. INDEX, ON,
 * USING and WITH are words only here, so they stay usable as names.
 */
public final class IndexParser {

    private final TokenStream tokenStream;
    private final ExpressionParser expressionParser;

    public IndexParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
        this.expressionParser = new ExpressionParser(tokenStream);
    }

    public Statement parseCreate() throws SQLSyntaxErrorException {
        expectWord("INDEX");

        boolean ifNotExists = this.tokenStream.peekWord("IF");
        if (ifNotExists) {
            this.tokenStream.advance();
            this.tokenStream.expectKeyword("NOT");
            expectWord("EXISTS");
        }

        String name = this.tokenStream.peekWord("ON") ? null : this.tokenStream.expectIdentifier();
        expectWord("ON");

        TableName table = new TableParser(this.tokenStream).parse();
        Column column = parseColumn();

        String using = null;
        if (matchWord("USING")) {
            using = word("Expected an index type, such as keyword or text");
        }

        var options = new LinkedHashMap<String, Expression>();
        if (matchWord("WITH")) {
            this.tokenStream.expectSymbol("(");
            do {
                String option = this.tokenStream.expectIdentifier().toLowerCase(Locale.ROOT);
                this.tokenStream.expectSymbol("=");

                if (options.put(option, parseValue()) != null) {
                    throw this.tokenStream.error("Option '" + option + "' is given twice");
                }
            } while (this.tokenStream.matchSymbol(","));
            this.tokenStream.expectSymbol(")");
        }

        return new CreateIndexStatement(table, column, name, using, options, ifNotExists);
    }

    public Statement parseDrop() throws SQLSyntaxErrorException {
        expectWord("INDEX");

        boolean ifExists = this.tokenStream.peekWord("IF")
            && this.tokenStream.peekWordAfterNext("EXISTS");
        if (ifExists) {
            this.tokenStream.advance();
            this.tokenStream.advance();
        }

        if (matchWord("ON")) {
            TableName table = new TableParser(this.tokenStream).parse();
            return new DropIndexStatement(table, null, parseColumn(), ifExists);
        }

        String name = this.tokenStream.expectIdentifier();
        expectWord("ON");

        return new DropIndexStatement(new TableParser(this.tokenStream).parse(), name, null,
            ifExists);
    }

    // The one field in brackets; Qdrant indexes a field at a time
    private Column parseColumn() throws SQLSyntaxErrorException {
        this.tokenStream.expectSymbol("(");
        Column column = this.expressionParser.parseColumn();

        if (this.tokenStream.peekSymbol(",")) {
            throw this.tokenStream.error("Expected ): a Qdrant payload index is on one field");
        }

        this.tokenStream.expectSymbol(")");
        return column;
    }

    // A constant, or a bare word as text, as in WITH (tokenizer = word)
    private Expression parseValue() throws SQLSyntaxErrorException {
        if (this.tokenStream.peekIdentifier()) {
            return new Literal(this.tokenStream.advance().text());
        }

        return this.expressionParser.parseValue();
    }

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
