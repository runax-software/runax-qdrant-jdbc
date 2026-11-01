package com.runaxsoftware.qdrant.jdbc.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.VectorLiteral;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Turns a value given to a prepared statement into the constant it stands for in the statement:
 * text, a whole or decimal number, a boolean, a vector, or null. Date-times become RFC 3339 text
 * in UTC, which is how Qdrant compares them.
 */
final class ParameterValues {

    private ParameterValues() {
    }

    static Expression of(Object value) throws SQLException {
        if (value == null) return new Literal(null);
        if (value instanceof String || value instanceof Boolean) return new Literal(value);
        if (value instanceof Long) return new Literal(value);
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return new Literal(((Number) value).longValue());
        }
        if (value instanceof Double) return new Literal(value);
        if (value instanceof Float number) return new Literal(Double.parseDouble(number.toString()));
        if (value instanceof BigInteger number) return new Literal(wholeNumber(number));
        if (value instanceof BigDecimal number) return decimal(number);
        if (value instanceof UUID uuid) return new Literal(uuid.toString());
        if (value instanceof Character character) return new Literal(character.toString());

        if (value instanceof java.sql.Timestamp timestamp) return dateTime(timestamp.toInstant());
        if (value instanceof java.sql.Date date) return startOfDay(date.toLocalDate());
        if (value instanceof java.sql.Time) {
            throw new SQLFeatureNotSupportedException("A time of day isn't a value Qdrant "
                + "compares: give a timestamp");
        }
        if (value instanceof java.util.Date date) return dateTime(date.toInstant());
        if (value instanceof Instant instant) return dateTime(instant);
        if (value instanceof OffsetDateTime dateTime) return dateTime(dateTime.toInstant());
        if (value instanceof ZonedDateTime dateTime) return dateTime(dateTime.toInstant());
        if (value instanceof LocalDate date) return startOfDay(date);

        if (value instanceof float[] floats) {
            var values = new ArrayList<Double>(floats.length);
            for (float x : floats) values.add(Double.parseDouble(Float.toString(x)));
            return vector(values);
        }
        if (value instanceof double[] doubles) {
            var values = new ArrayList<Double>(doubles.length);
            for (double x : doubles) values.add(x);
            return vector(values);
        }
        if (value instanceof Number[] numbers) return vector(List.of(numbers));
        if (value instanceof Collection<?> collection) return vector(collection);

        throw new SQLFeatureNotSupportedException("A parameter of type "
            + value.getClass().getName() + " isn't supported: give text, a number, a boolean, a "
            + "date-time, a UUID, or a vector as float[] or double[]");
    }

    private static long wholeNumber(BigInteger number) throws SQLException {
        try {
            return number.longValueExact();
        } catch (ArithmeticException e) {
            throw new SQLException("The number " + number + " is too large for a parameter",
                "22003", e);
        }
    }

    // A decimal that holds a whole number is one, so 3.0 still matches a whole-number field
    private static Literal decimal(BigDecimal number) throws SQLException {
        BigDecimal stripped = number.stripTrailingZeros();

        return stripped.scale() <= 0
            ? new Literal(wholeNumber(stripped.toBigIntegerExact()))
            : new Literal(number.doubleValue());
    }

    private static Literal dateTime(Instant instant) {
        return new Literal(instant.toString());
    }

    private static Literal startOfDay(LocalDate date) {
        return dateTime(date.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static VectorLiteral vector(Collection<?> values) throws SQLException {
        if (values.isEmpty()) {
            throw new SQLException("An empty vector searches nothing", "22023");
        }

        var doubles = new ArrayList<Double>(values.size());
        for (Object value : values) {
            if (!(value instanceof Number number)) {
                throw new SQLFeatureNotSupportedException("A vector parameter holds numbers, not "
                    + (value == null ? "null" : value.getClass().getName()));
            }

            doubles.add(number.doubleValue());
        }

        return new VectorLiteral(doubles);
    }
}
