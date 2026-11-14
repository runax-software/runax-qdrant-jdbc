package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ExpressionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AddColumnStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AlterSettingsStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropColumnStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads {@code ALTER TABLE docs ADD [COLUMN] [IF NOT EXISTS] column},
 * {@code ALTER TABLE docs DROP [COLUMN] [IF EXISTS] name}, {@code ALTER TABLE docs SET (setting
 * = value, ...)} and {@code ALTER TABLE docs ALTER [COLUMN] name SET (...)}, and hands
 * {@code ALTER VIEW} to the view parser. ADD, COLUMN, IF and EXISTS are words only here.
 */
public final class AlterParser {

    private final TokenStream tokenStream;

    public AlterParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public Statement parse() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("ALTER");

        if (!this.tokenStream.matchKeyword("TABLE")) {
            if (!this.tokenStream.peekWord("VIEW")) {
                throw this.tokenStream.error("Expected TABLE or VIEW");
            }

            return new ViewParser(this.tokenStream).parseAlter();
        }

        TableName table = new TableParser(this.tokenStream).parse();

        if (this.tokenStream.matchWord("ADD")) {
            this.tokenStream.matchWord("COLUMN");

            boolean ifNotExists = this.tokenStream.peekWord("IF");
            if (ifNotExists) {
                this.tokenStream.advance();
                this.tokenStream.expectKeyword("NOT");
                expectWord("EXISTS");
            }

            return new AddColumnStatement(table, new ColumnParser(this.tokenStream).parse(),
                ifNotExists);
        }

        if (this.tokenStream.matchKeyword("DROP")) {
            this.tokenStream.matchWord("COLUMN");

            boolean ifExists = this.tokenStream.peekWord("IF")
                && this.tokenStream.peekWordAfterNext("EXISTS");
            if (ifExists) {
                this.tokenStream.advance();
                this.tokenStream.advance();
            }

            return new DropColumnStatement(table, this.tokenStream.expectIdentifier(), ifExists);
        }

        if (this.tokenStream.peekKeyword("SET")) {
            return new AlterSettingsStatement(table, null, parseSettings());
        }

        if (this.tokenStream.matchKeyword("ALTER")) {
            this.tokenStream.matchWord("COLUMN");
            String column = this.tokenStream.expectIdentifier();

            if (!this.tokenStream.peekKeyword("SET")) {
                throw this.tokenStream.error("Expected SET (...): a vector's settings are what "
                    + "ALTER COLUMN changes");
            }

            return new AlterSettingsStatement(table, column, parseSettings());
        }

        throw this.tokenStream.error("Expected ADD COLUMN, DROP COLUMN, ALTER COLUMN or SET");
    }

    // SET (name = value, ...), a name being words joined by dots, as in hnsw.m
    private Map<String, Expression> parseSettings() throws SQLSyntaxErrorException {
        this.tokenStream.expectKeyword("SET");
        this.tokenStream.expectSymbol("(");

        var settings = new LinkedHashMap<String, Expression>();
        do {
            var name = new StringBuilder(this.tokenStream.expectIdentifier());
            while (this.tokenStream.matchSymbol(".")) {
                name.append('.').append(this.tokenStream.expectIdentifier());
            }

            this.tokenStream.expectSymbol("=");

            if (settings.put(name.toString(), parseValue()) != null) {
                throw this.tokenStream.error("Setting '" + name + "' is given twice");
            }
        } while (this.tokenStream.matchSymbol(","));

        this.tokenStream.expectSymbol(")");
        return settings;
    }

    // A constant, or a bare word as text, as in SET (modifier = idf)
    private Expression parseValue() throws SQLSyntaxErrorException {
        if (this.tokenStream.peekIdentifier()) {
            return new Literal(this.tokenStream.advance().text());
        }

        return new ExpressionParser(this.tokenStream).parseValue();
    }

    private void expectWord(String word) throws SQLSyntaxErrorException {
        if (!this.tokenStream.matchWord(word)) {
            throw this.tokenStream.error("Expected " + word);
        }
    }
}
