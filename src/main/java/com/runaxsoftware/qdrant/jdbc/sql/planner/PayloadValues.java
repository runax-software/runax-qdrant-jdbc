package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;

/**
 * The JSON a payload field is written as, from the constant a statement gives it. A value is
 * read as the column's type: tools send what a user typed into a cell as text, so a number,
 * boolean, or JSON column takes text that holds one. A field the collection doesn't have yet
 * takes the constant as it is.
 */
final class PayloadValues {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadValues() {
    }

    /**
     * The value as JSON, or {@code null} for NULL.
     *
     * @param column the column, or {@code null} for a field the collection doesn't have yet
     * @param name   the column's name, for errors
     */
    static JsonNode of(Expression value, ColumnDefinition column, String name) throws SQLException {
        if (value instanceof Parameter) {
            throw new SQLFeatureNotSupportedException("Parameters are given through a "
                + "PreparedStatement");
        }
        if (!(value instanceof Literal literal)) {
            throw new SQLFeatureNotSupportedException("'" + name + "' can only be given a text, "
                + "number, boolean, or NULL constant");
        }

        Object constant = literal.value();
        if (constant == null) {
            return null;
        }

        ColumnType type = column == null ? null : column.type();
        if (type == null) {
            return node(constant);
        }

        return switch (type) {
            case INTEGER -> wholeNumber(constant, name);
            case FLOAT -> number(constant, name);
            case BOOLEAN -> bool(constant, name);
            case JSON -> json(constant, name);
            default -> JSON.textNode(constant.toString());
        };
    }

    private static JsonNode node(Object constant) {
        if (constant instanceof Long number) return JSON.numberNode(number);
        if (constant instanceof Double number) return JSON.numberNode(number);
        if (constant instanceof Boolean bool) return JSON.booleanNode(bool);

        return JSON.textNode((String) constant);
    }

    private static JsonNode wholeNumber(Object constant, String name) throws SQLException {
        BigDecimal number = decimal(constant, name, "a whole number");

        try {
            return JSON.numberNode(number.longValueExact());
        } catch (ArithmeticException e) {
            throw invalid(constant, name, "a whole number");
        }
    }

    private static JsonNode number(Object constant, String name) throws SQLException {
        return JSON.numberNode(decimal(constant, name, "a number").doubleValue());
    }

    private static BigDecimal decimal(Object constant, String name, String expected)
        throws SQLException {
        if (constant instanceof Long number) return BigDecimal.valueOf(number);
        if (constant instanceof Double number) return BigDecimal.valueOf(number);

        if (constant instanceof String text) {
            try {
                return new BigDecimal(text.strip());
            } catch (NumberFormatException e) {
                throw invalid(constant, name, expected);
            }
        }

        throw invalid(constant, name, expected);
    }

    private static JsonNode bool(Object constant, String name) throws SQLException {
        if (constant instanceof Boolean bool) return JSON.booleanNode(bool);

        if (constant instanceof String text) {
            String lower = text.strip().toLowerCase(Locale.ROOT);
            if (lower.equals("true") || lower.equals("false")) {
                return JSON.booleanNode(Boolean.parseBoolean(lower));
            }
        }

        throw invalid(constant, name, "true or false");
    }

    // JSON written as text, as a JSON column's cells are; numbers and booleans are JSON too
    private static JsonNode json(Object constant, String name) throws SQLException {
        if (!(constant instanceof String text)) {
            return node(constant);
        }

        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new SQLException("'" + name + "' is a JSON column, and " + shown(constant)
                + " isn't JSON: write text in double quotes, as '\"text\"'", "22018", e);
        }
    }

    private static SQLException invalid(Object constant, String name, String expected) {
        return new SQLException("'" + name + "' takes " + expected + ", not " + shown(constant),
            "22018");
    }

    private static String shown(Object constant) {
        return constant instanceof String text ? "'" + text + "'" : String.valueOf(constant);
    }
}
