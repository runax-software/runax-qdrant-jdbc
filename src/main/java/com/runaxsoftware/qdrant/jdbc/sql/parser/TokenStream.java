package com.runaxsoftware.qdrant.jdbc.sql.parser;

import java.sql.SQLSyntaxErrorException;
import java.util.List;

public final class TokenStream {

    private final List<Token> tokens;
    private int pos;
    private int parameterCount;

    public TokenStream(List<Token> tokens) {
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

        return new SQLSyntaxErrorException(message + ", found " + found + " at position " + token.position());
    }
}
