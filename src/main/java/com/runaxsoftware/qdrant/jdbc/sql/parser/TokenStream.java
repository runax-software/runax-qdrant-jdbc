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

    /**
     * Whether the next token is this word, whatever its case. For words such as EXISTS that
     * mean something only in one place, so they stay usable as names everywhere else.
     */
    public boolean peekWord(String word) {
        return peek().type() == TokenType.IDENTIFIER && peek().text().equalsIgnoreCase(word);
    }

    /**
     * Reads the next token when it's this word, whatever its case, as {@link #matchKeyword}
     * reads a keyword.
     */
    public boolean matchWord(String word) {
        if (peekWord(word)) {
            advance();

            return true;
        }

        return false;
    }

    /**
     * Whether the token after the next one is this word, whatever its case.
     */
    public boolean peekWordAfterNext(String word) {
        if (pos + 1 >= tokens.size()) return false;

        Token next = tokens.get(pos + 1);
        return next.type() == TokenType.IDENTIFIER && next.text().equalsIgnoreCase(word);
    }

    public boolean peekSymbol(String symbol) {
        return peek().type() == TokenType.SYMBOL && peek().text().equals(symbol);
    }

    public boolean matchSymbol(String symbol) {
        if (peekSymbol(symbol)) {
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

    /**
     * A number, whole or not, such as one value of a vector literal.
     */
    public double expectNumber() throws SQLSyntaxErrorException {
        Token token = peek();

        if (token.type() != TokenType.NUMBER) {
            throw error("Expected a number");
        }

        advance();
        return Double.parseDouble(token.text());
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

    /**
     * An error at the token the stream is on, for SQL the driver reads and doesn't do: the
     * message says what that is, so only the place is added, where {@link #error} also names
     * the token it found instead of what it expected.
     *
     * @param sqlState the SQL state to report, as the planner reports one for SQL it can't run
     */
    public SQLSyntaxErrorException errorAt(String message, String sqlState) {
        return new SQLSyntaxErrorException(
            message + " at " + SourceLocation.describe(sql, peek().position()), sqlState);
    }
}
