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

        Expression column = parseTerm();

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

    /**
     * A column, or a call such as {@code similarity(dense, [0.1, 0.2])}. Which of the two a
     * name is, the brackets after it decide.
     */
    public Expression parseTerm() throws SQLSyntaxErrorException {
        if (!this.tokenStream.peekIdentifier()) {
            return parseColumn();   // reports what it expected
        }

        String name = this.tokenStream.expectIdentifier();
        if (!this.tokenStream.peekSymbol("(")) {
            return this.tokenStream.matchSymbol(".")
                ? new Column(name, this.tokenStream.expectIdentifier())
                : Column.of(name);
        }

        return parseCall(name);
    }

    /**
     * The arguments of a call whose name has already been read, as a select list does for
     * {@code COUNT(*)}: there a name followed by brackets is a call and not a column, which
     * only the brackets tell apart.
     */
    public Expression parseCall(String name) throws SQLSyntaxErrorException {
        this.tokenStream.expectSymbol("(");

        var arguments = new ArrayList<Expression>();
        if (!this.tokenStream.matchSymbol(")")) {
            do {
                arguments.add(parseArgument());
            } while (this.tokenStream.matchSymbol(","));

            this.tokenStream.expectSymbol(")");
        }

        return new FunctionCall(name, arguments);
    }

    /**
     * A column name, which the statement may name under the table or an alias, as in
     * {@code t.name}. A nested payload field is one quoted name, {@code "specs.color"}, so it
     * reads as a column and not as a qualified one.
     */
    public Column parseColumn() throws SQLSyntaxErrorException {
        String name = this.tokenStream.expectIdentifier();

        return this.tokenStream.matchSymbol(".")
            ? new Column(name, this.tokenStream.expectIdentifier())
            : Column.of(name);
    }

    /**
     * One argument of a call: a star, as {@code COUNT(*)} counts whole rows, a column, a nested
     * call, or a value such as a vector literal.
     */
    private Expression parseArgument() throws SQLSyntaxErrorException {
        if (this.tokenStream.matchSymbol("*")) {
            return Star.of();
        }
        if (this.tokenStream.peekKeyword("DISTINCT")) {
            throw this.tokenStream.errorAt("DISTINCT isn't supported in a call: Qdrant counts "
                + "points, and a field's distinct values come from its facet API", "42000");
        }

        return parseValueOrCall();
    }

    /**
     * A value, or a name: a call such as {@code embed('text')}, or a column, which the planner
     * says whether it may use there.
     */
    public Expression parseValueOrCall() throws SQLSyntaxErrorException {
        return this.tokenStream.peekIdentifier() ? parseTerm() : parseValue();
    }

    public Expression parseValue() throws SQLSyntaxErrorException {
        if (this.tokenStream.peekSymbol("[")) {
            return parseVector();
        }

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
                case "NULL" -> new Literal(null);
                default -> throw this.tokenStream.error("Expected a value");
            };
            default -> throw this.tokenStream.error("Expected a value");
        };

        this.tokenStream.advance();
        return value;
    }

    /**
     * A dense vector written out, as {@code [0.1, 0.2]}. An empty one searches nothing, so it's
     * an error rather than a vector of no dimensions.
     */
    private VectorLiteral parseVector() throws SQLSyntaxErrorException {
        this.tokenStream.expectSymbol("[");
        var values = new ArrayList<Double>();

        do {
            values.add(this.tokenStream.expectNumber());
        } while (this.tokenStream.matchSymbol(","));

        this.tokenStream.expectSymbol("]");

        return new VectorLiteral(values);
    }
}
