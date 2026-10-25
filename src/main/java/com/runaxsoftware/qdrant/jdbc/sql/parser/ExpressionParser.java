package com.runaxsoftware.qdrant.jdbc.sql.parser;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.*;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.Set;

public final class ExpressionParser {

    private static final Set<String> COMPARISONS = Set.of("=", "<>", "!=", "<", "<=", ">", ">=");
    private final TokenStream tokenStream;

    public ExpressionParser(TokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    public Expression parseOr() throws SQLSyntaxErrorException {
        Expression left = parseAnd();

        while (this.tokenStream.matchKeyword("OR")) {
            left = new Or(left, parseAnd());
        }

        return left;
    }

    public Expression parseAnd() throws SQLSyntaxErrorException {
        Expression left = parseNot();

        while (this.tokenStream.matchKeyword("AND")) {
            left = new And(left, parseNot());
        }

        return left;
    }

    public Expression parseNot() throws SQLSyntaxErrorException {
        return this.tokenStream.matchKeyword("NOT") ? new Not(parseNot()) : parseCondition();
    }

    public Expression parseCondition() throws SQLSyntaxErrorException {
        if (this.tokenStream.matchSymbol("(")) {
            Expression inner = parseOr();

            this.tokenStream.expectSymbol(")");

            return inner;
        }

        Expression column = new Column(this.tokenStream.expectIdentifier());

        if (this.tokenStream.matchKeyword("IS")) {
            boolean negated = this.tokenStream.matchKeyword("NOT");

            this.tokenStream.expectKeyword("NULL");

            return new IsNull(column, negated);
        }

        boolean negated = this.tokenStream.matchKeyword("NOT");
        if (this.tokenStream.matchKeyword("IN")) {
            this.tokenStream.expectSymbol("(");

            var values = new ArrayList<Expression>();
            do {
                values.add(parseValue());
            } while (this.tokenStream.matchSymbol(","));

            this.tokenStream.expectSymbol(")");

            return new InList(column, values, negated);
        }
        if (negated) {
            throw this.tokenStream.error("Expected IN after NOT");
        }

        Token operator = this.tokenStream.peek();
        if (operator.type() == TokenType.SYMBOL && COMPARISONS.contains(operator.text())) {
            this.tokenStream.advance();

            return new Comparison(column, operator.text(), parseValue());
        }
        throw this.tokenStream.error("Expected a comparison such as =, <, IN or IS NULL");
    }

    public Expression parseValue() throws SQLSyntaxErrorException {
        Token token = this.tokenStream.peek();
        Expression value = switch (token.type()) {
            case STRING -> new Literal(token.text());
            case NUMBER -> new Literal(token.text().contains(".")
                ? (Object) Double.parseDouble(token.text())
                : (Object) Long.parseLong(token.text()));
            case PARAMETER -> new Parameter(this.tokenStream.nextParameterIndex());
            case KEYWORD -> switch (token.text()) {
                case "TRUE" -> new Literal(true);
                case "FALSE" -> new Literal(false);
                default -> throw this.tokenStream.error("Expected a value");
            };
            default -> throw this.tokenStream.error("Expected a value");
        };

        this.tokenStream.advance();
        return value;
    }
}
