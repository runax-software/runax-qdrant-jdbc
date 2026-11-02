package com.runaxsoftware.qdrant.jdbc.sql.parser;

import java.sql.SQLSyntaxErrorException;
import java.util.*;

final class Lexer {

    // GROUP and DISTINCT are keywords so that the SQL they belong to fails saying so, rather
    // than reading as a name and failing somewhere later
    private static final Set<String> KETWORDS = Set.of(
        "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS", "NULL",
        "TRUE", "FALSE", "ORDER", "BY", "ASC", "DESC", "LIMIT", "OFFSET", "AS",
        "GROUP", "DISTINCT", "DELETE", "TRUNCATE", "DROP", "TABLE", "CREATE", "SET", "UPDATE",
        "INSERT", "INTO", "VALUES", "ALTER");

    private static final List<String> TWO_CHAR_SYMBOLS = List.of(
        "<=", ">=", "<>", "!=");
    // '.' separates the parts of a qualified name; a number keeps its own decimal point, below.
    // '[' and ']' hold a vector literal. ';' may end a statement, as tools and scripts write.
    private static final String ONE_CHAR_SYMBOLS = "=<>(),*.[];";

    private final String sql;
    private int pos;

    Lexer(String sql) {
        this.sql = sql;
    }

    List<Token> tokenize() throws SQLSyntaxErrorException {
        var tokens = new ArrayList<Token>();

        while (true) {
            while (pos < sql.length() && Character.isWhitespace(sql.charAt(pos))) {
                pos++;
            }

            if (pos >= sql.length()) {
                tokens.add(new Token(TokenType.EOF, "", pos));

                return tokens;
            }

            tokens.add(next());
        }
    }

    private Token next() throws SQLSyntaxErrorException {
        int start = pos;
        char c = sql.charAt(pos);

        if (Character.isLetter(c) || c == '_') {
            while (pos < sql.length()
                && (Character.isLetterOrDigit(sql.charAt(pos)) || sql.charAt(pos) == '_')) {
                pos++;
            }

            String word = sql.substring(start, pos);
            String upper = word.toUpperCase(Locale.ROOT);

            return KETWORDS.contains(upper)
                ? new Token(TokenType.KEYWORD, upper, start)
                : new Token(TokenType.IDENTIFIER, word, start);
        }

        if (Character.isDigit(c) || (c == '-' && isDiditAt(pos + 1))) {
            do {
                pos++;
            } while (isDiditAt(pos));

            if (pos < sql.length() && sql.charAt(pos) == '.' && isDiditAt(pos + 1)) {
                do {
                    pos++;
                } while (isDiditAt(pos));
            }

            return new Token(TokenType.NUMBER, sql.substring(start, pos), start);
        }

        if (c == '\'') {
            return quoted('\'', TokenType.STRING);
        }

        if (c == '"') {
            return quoted('"', TokenType.IDENTIFIER);
        }

        if (c == '?') {
            pos++;

            return new Token(TokenType.PARAMETER, "?", start);
        }

        for (String symbol : TWO_CHAR_SYMBOLS) {
            if (sql.startsWith(symbol, pos)) {
                pos += 2;

                return new Token(TokenType.SYMBOL, symbol, start);
            }
        }

        if (ONE_CHAR_SYMBOLS.indexOf(c) >= 0) {
            pos++;

            return new Token(TokenType.SYMBOL, String.valueOf(c), start);
        }

        throw new SQLSyntaxErrorException("Unexpected character '" + c + "' at "
            + SourceLocation.describe(sql, start));
    }

    private Token quoted(char quote, TokenType type) throws SQLSyntaxErrorException {
        int start = pos++;
        var text = new StringBuilder();

        while (pos < sql.length()) {
            char c = sql.charAt(pos++);

            if (c != quote) {
                text.append(c);
            } else if (pos < sql.length() && sql.charAt(pos) == quote) {
                text.append(quote);
                pos++;
            } else {
                return new Token(type, text.toString(), start);
            }
        }

        throw new SQLSyntaxErrorException("Unclosed quote starting at "
            + SourceLocation.describe(sql, start));
    }

    private boolean isDiditAt(int index) {
        return index < sql.length() && Character.isDigit(sql.charAt(index));
    }
}
