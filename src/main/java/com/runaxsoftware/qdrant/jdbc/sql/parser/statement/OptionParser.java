package com.runaxsoftware.qdrant.jdbc.sql.parser.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ExpressionParser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.TokenStream;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ResetStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SetStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ShowStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the statements about the connection's own options: {@code SET name = value} (or
 * {@code TO value}), {@code RESET name}, {@code RESET ALL}, {@code SHOW name}, and
 * {@code SHOW ALL}. RESET, SHOW, ALL, TO and DEFAULT are read as words only here, so they stay
 * usable as names elsewhere. A value may also be a bare word, as {@code majority} is in
 * {@code SET qdrant.consistency = majority}.
 */
public final class OptionParser {

    private final TokenStream tokenStream;

    public OptionParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public Statement parse() throws SQLSyntaxErrorException {
        if (this.tokenStream.matchKeyword("SET")) {
            List<String> name = parseName();

            if (!this.tokenStream.matchSymbol("=") && !matchWord("TO")) {
                throw this.tokenStream.error("Expected = or TO");
            }

            return new SetStatement(name, parseValue());
        }

        if (matchWord("RESET")) {
            return new ResetStatement(matchWord("ALL") ? null : parseName());
        }

        expectWord("SHOW");
        return new ShowStatement(matchWord("ALL") ? null : parseName());
    }

    // An option's name: qdrant.consistency, or a single word that the planner rejects
    private List<String> parseName() throws SQLSyntaxErrorException {
        var parts = new ArrayList<String>();

        do {
            parts.add(this.tokenStream.expectIdentifier());
        } while (this.tokenStream.matchSymbol("."));

        return parts;
    }

    // A constant, a bare word as text, or DEFAULT, which is null
    private Expression parseValue() throws SQLSyntaxErrorException {
        if (matchWord("DEFAULT")) {
            return null;
        }
        if (this.tokenStream.peekIdentifier()) {
            return new Literal(this.tokenStream.advance().text());
        }

        return new ExpressionParser(this.tokenStream).parseValue();
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
