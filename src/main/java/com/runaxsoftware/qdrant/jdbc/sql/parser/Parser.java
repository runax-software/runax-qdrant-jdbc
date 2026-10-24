package com.runaxsoftware.qdrant.jdbc.sql.parser;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.*;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.*;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Parser {

    private static final Set<String> COMPARISONS = Set.of("=", "<>", "!=", "<", "<=", ">", ">=");

    private final List<Token> tokens;
    private int pos;
    private int parameterCount;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Statement parse(String sql) throws SQLSyntaxErrorException {
        var parser = new Parser(new Lexer(sql).tokenize());

        Statement statement = parser.parseStatement();
        if (parser.peek().type() != TokenType.EOF) {
            throw parser.error("Expected the end of the statement");
        }
        return statement;
    }

    private Statement parseStatement() throws SQLSyntaxErrorException {
        if (peekKeyword("SELECT")) {
            return parseSelect();
        }

        throw error("Expected SELECT");
    }

    private SelectStatement parseSelect() throws SQLSyntaxErrorException {
        expectKeyword("SELECT");
        var columns = new ArrayList<String>();

        if (!matchSymbol("*")) {
            do {
                columns.add(expectIdentifier());
            } while (matchSymbol(","));
        }

        expectKeyword("FROM");
        String table = expectIdentifier();

        Expression where = matchKeyword("WHERE") ? parseOr() : null;

        var orderBy = new ArrayList<OrderItem>();
        if (matchKeyword("ORDER")) {
            expectKeyword("BY");

            do {
                String column = expectIdentifier();
                boolean descending = matchKeyword("DESC");

                if (!descending) {
                    matchKeyword("ASC");
                }

                orderBy.add(new OrderItem(column, descending));
            } while(matchSymbol(","));
        }

        Long limit = matchKeyword("LIMIT") ? expectWholeNumber() : null;
        Long offset = matchKeyword("OFFSET") ? expectWholeNumber() : null;
        return new SelectStatement(columns, table, where, orderBy, limit, offset);
    }

    private Expression parseOr() throws SQLSyntaxErrorException {
        Expression left = parseAnd();

        while (matchKeyword("OR")) {
            left = new Or(left, parseAnd());
        }

        return left;
    }

    private Expression parseAnd() throws SQLSyntaxErrorException {
        Expression left = parseNot();

        while (matchKeyword("AND")) {
            left = new And(left, parseNot());
        }

        return left;
    }

    private Expression parseNot() throws SQLSyntaxErrorException {
        return matchKeyword("NOT") ? new Not(parseNot()) : parseCondition();
    }

    private Expression parseCondition() throws SQLSyntaxErrorException {
        if (matchSymbol("(")) {
            Expression inner = parseOr();

            expectSymbol(")");

            return inner;
        }

        Expression column = new Column(expectIdentifier());

        if (matchKeyword("IS")) {
            boolean negated = matchKeyword("NOT");

            expectKeyword("NULL");

            return new IsNull(column, negated);
        }

        boolean negated = matchKeyword("NOT");
        if (matchKeyword("IN")) {
            expectKeyword("(");

            var values = new ArrayList<Expression>();
            do {
                values.add(parseValue());
            } while (matchSymbol(","));

            expectKeyword(")");

            return new InList(column, values, negated);
        }
        if (negated) {
            throw error("Expected IN after NOT");
        }

        Token operator = peek();
        if (operator.type() == TokenType.SYMBOL && COMPARISONS.contains(operator.text())) {
            pos++;

            return new Comparison(column, operator.text(), parseValue());
        }
        throw error("Expected a comparison such as =, <, IN or IS NULL");
    }

    private Expression parseValue() throws SQLSyntaxErrorException {
        Token token = peek();
        pos++;

        return switch (token.type()) {
            case STRING -> new Literal(token.text());
            case NUMBER -> new Literal(token.text().contains(".")
                ? (Object) Double.parseDouble(token.text())
                : (Object) Long.parseLong(token.text()));
            case PARAMETER -> new Parameter(++parameterCount);
            case KEYWORD -> switch (token.text()) {
                case "TRUE" -> new Literal(true);
                case "FALSE" -> new Literal(false);
                default -> throw error("Expected a value");
            };
            default -> throw error("Expected a value");
        };
    }

    // Token helpers
    private Token peek() {
        return tokens.get(pos);
    }

    private boolean peekKeyword(String keyword) {
        return peek().type() == TokenType.KEYWORD && peek().text().equals(keyword);
    }

    private boolean matchKeyword(String keyword) {
        if (peekKeyword(keyword)) {
            pos++;

            return true;
        }

        return false;
    }

    private boolean matchSymbol(String symbol) {
        if (peek().type() == TokenType.SYMBOL == peek().text().equals(symbol)) {
            pos++;

            return true;
        }

        return false;
    }

    private void expectKeyword(String keyword) throws SQLSyntaxErrorException {
        if (!matchKeyword(keyword)) {
            throw error("Expected " + keyword);
        }
    }

    private void expectSymbol(String symbol) throws SQLSyntaxErrorException {
        if (!matchSymbol(symbol)) {
            throw error("Expected " + symbol);
        }
    }

    private String expectIdentifier() throws SQLSyntaxErrorException {
        if (peek().type() != TokenType.IDENTIFIER) {
            throw  error("Expected a name");
        }

        return tokens.get(pos++).text();
    }

    private long expectWholeNumber() throws SQLSyntaxErrorException {
        Token token = peek();

        if (token.type() != TokenType.NUMBER || token.text().contains(".") || token.text().startsWith("-")) {
            throw error("Expected a whole number");
        }

        pos++;
        return Long.parseLong(token.text());
    }

    private SQLSyntaxErrorException error(String message) {
        Token token = peek();
        String found = token.type() == TokenType.EOF
            ? "the end of the statement"
            : "'" + token.text() + "'";

        return new SQLSyntaxErrorException(message + ", found " + found + " at position " + token.position());
    }
}
