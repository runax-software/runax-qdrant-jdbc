package com.runaxsoftware.qdrant.jdbc.sql.parser;

import java.sql.SQLSyntaxErrorException;
import java.util.List;

public final class TokenStream {

    private final String sql;
    private final List<Token> tokens;
    private int pos;
    private int parameterCount;

    /**
     * @param sql    the SQL text the tokens come from, for the line and column in errors
     * @param tokens the tokens, ending with an EOF token
     */
    public TokenStream(String sql, List<Token> tokens) {
        this.sql = sql;
        this.tokens = tokens;
    }

    public Token peek() {
        return tokens.get(pos);
    }

    public Token advance() {
        Token token = peek();

        if (token.type() != TokenType.EOF) {
            pos++;
        }

        return token;
    }

    public int nextParameterIndex() {
        return ++parameterCount;
    }

    public boolean peekIdentifier() {
        return peek().type() == TokenType.IDENTIFIER;
    }

    public boolean peekKeyword(String keyword) {
        return peek().type() == TokenType.KEYWORD && peek().text().equals(keyword);
    }

    public boolean matchKeyword(String keyword) {
        if (peekKeyword(keyword)) {
            advance();

            return true;
        }

        return false;
    }

    public boolean matchSymbol(String symbol) {
        if (peek().type() == TokenType.SYMBOL && peek().text().equals(symbol)) {
            advance();

            return true;
        }

        return false;
    }

    public void expectKeyword(String keyword) throws SQLSyntaxErrorException {
        if (!matchKeyword(keyword)) {
            throw error("Expected " + keyword);
        }
    }

    public void expectSymbol(String symbol) throws SQLSyntaxErrorException {
        if (!matchSymbol(symbol)) {
            throw error("Expected " + symbol);
        }
    }

    public String expectIdentifier() throws SQLSyntaxErrorException {
        if (peek().type() != TokenType.IDENTIFIER) {
            throw  error("Expected a name");
        }

        return advance().text();
    }

    public long expectWholeNumber() throws SQLSyntaxErrorException {
        Token token = peek();

        if (token.type() != TokenType.NUMBER || token.text().contains(".") || token.text().startsWith("-")) {
            throw error("Expected a whole number");
        }

        advance();
        return Long.parseLong(token.text());
    }

    public SQLSyntaxErrorException error(String message) {
        Token token = peek();
        String found = token.type() == TokenType.EOF
            ? "the end of the statement"
            : "'" + token.text() + "'";

        return new SQLSyntaxErrorException(
            message + ", found " + found + " at " + SourceLocation.describe(sql, token.position()));
    }
}
