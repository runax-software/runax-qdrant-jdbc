package com.runaxsoftware.qdrant.jdbc.resultset;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Converts the values in a result set to the Java types the JDBC getters ask for. Values are
 * {@code String}, {@code Boolean}, {@code Short}, {@code Integer}, {@code Long},
 * {@code Double}, {@code BigDecimal}, or {@code OffsetDateTime}, or {@code null}.
 */
final class ValueConversions {

    private ValueConversions() {
    }

    static String toText(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }

        return value.toString();
    }

    static boolean toBoolean(Object value) throws SQLException {
        if (value == null) {
            return false;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        if (value instanceof Number) {
            return toBigDecimal(value).signum() != 0;
        }

        String text = value.toString().strip();
        if (text.equalsIgnoreCase("true") || text.equals("1")) {
            return true;
        }

        if (text.equalsIgnoreCase("false") || text.equals("0")) {
            return false;
        }

        throw cannotConvert(value, "BOOLEAN");
    }

    static long toLong(Object value, String targetType, long min, long max) throws SQLException {
        if (value == null) {
            return 0;
        }

        BigDecimal number = toBigDecimal(value).setScale(0, RoundingMode.DOWN);
        if (number.compareTo(BigDecimal.valueOf(min)) < 0
            || number.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new SQLDataException(
                "Value " + toText(value) + " is out of range for " + targetType, "22003");
        }

        return number.longValue();
    }

    static double toDouble(Object value) throws SQLException {
        if (value == null) {
            return 0;
        }

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        if (value instanceof Boolean) {
            return (Boolean) value ? 1 : 0;
        }

        try {
            return Double.parseDouble(value.toString().strip());
        } catch (NumberFormatException e) {
            throw cannotConvert(value, "DOUBLE");
        }
    }

    static BigDecimal toBigDecimal(Object value) throws SQLException {
        if (value == null) {
            return null;
        }

        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }

        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }

        if (value instanceof Long || value instanceof Integer
            || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }

        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();

            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw cannotConvert(value, "DECIMAL");
            }

            return BigDecimal.valueOf(number);
        }

        if (value instanceof Boolean) {
            return (Boolean) value ? BigDecimal.ONE : BigDecimal.ZERO;
        }

        try {
            return new BigDecimal(value.toString().strip());
        } catch (NumberFormatException e) {
            throw cannotConvert(value, "DECIMAL");
        }
    }

    /**
     * Reads a date-time. Text is accepted in the formats Qdrant accepts for datetime payloads:
     * with an offset, without one (taken as UTC), or a plain date.
     */
    static OffsetDateTime toOffsetDateTime(Object value) throws SQLException {
        if (value == null) {
            return null;
        }

        if (value instanceof OffsetDateTime) {
            return (OffsetDateTime) value;
        }

        if (value instanceof Instant) {
            return ((Instant) value).atOffset(ZoneOffset.UTC);
        }

        String text = value.toString().strip();
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // Try the formats without an offset next.
        }

        try {
            return LocalDateTime.parse(text).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Try a plain date next.
        }

        try {
            return LocalDate.parse(text).atStartOfDay().atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new SQLDataException("Can't read " + text + " as a date-time", "22007", e);
        }
    }

    static Timestamp toTimestamp(Object value) throws SQLException {
        OffsetDateTime dateTime = toOffsetDateTime(value);
        return dateTime == null ? null : Timestamp.from(dateTime.toInstant());
    }

    static Date toDate(Object value) throws SQLException {
        OffsetDateTime dateTime = toOffsetDateTime(value);
        return dateTime == null ? null : Date.valueOf(dateTime.toLocalDate());
    }

    static Time toTime(Object value) throws SQLException {
        OffsetDateTime dateTime = toOffsetDateTime(value);
        return dateTime == null ? null : Time.valueOf(dateTime.toLocalTime());
    }

    /**
     * Converts to the type {@code getObject(column, type)} asks for.
     */
    static <T> T toType(Object value, Class<T> type) throws SQLException {
        if (value == null) {
            return null;
        }

        if (type.isInstance(value)) {
            return type.cast(value);
        }

        if (type == String.class) {
            return type.cast(toText(value));
        }

        if (type == Boolean.class) {
            return type.cast(toBoolean(value));
        }

        if (type == Long.class) {
            return type.cast(toLong(value, "BIGINT", Long.MIN_VALUE, Long.MAX_VALUE));
        }

        if (type == Integer.class) {
            return type.cast((int) toLong(value, "INTEGER", Integer.MIN_VALUE, Integer.MAX_VALUE));
        }

        if (type == Short.class) {
            return type.cast((short) toLong(value, "SMALLINT", Short.MIN_VALUE, Short.MAX_VALUE));
        }

        if (type == Double.class) {
            return type.cast(toDouble(value));
        }

        if (type == Float.class) {
            return type.cast((float) toDouble(value));
        }

        if (type == BigDecimal.class) {
            return type.cast(toBigDecimal(value));
        }

        if (type == OffsetDateTime.class) {
            return type.cast(toOffsetDateTime(value));
        }

        if (type == Instant.class) {
            return type.cast(toOffsetDateTime(value).toInstant());
        }

        if (type == LocalDateTime.class) {
            return type.cast(toOffsetDateTime(value).toLocalDateTime());
        }

        if (type == LocalDate.class) {
            return type.cast(toOffsetDateTime(value).toLocalDate());
        }

        if (type == LocalTime.class) {
            return type.cast(toOffsetDateTime(value).toLocalTime());
        }

        if (type == Timestamp.class) {
            return type.cast(toTimestamp(value));
        }

        if (type == Date.class) {
            return type.cast(toDate(value));
        }

        if (type == Time.class) {
            return type.cast(toTime(value));
        }

        throw cannotConvert(value, type.getName());
    }

    private static SQLDataException cannotConvert(Object value, String targetType) {
        return new SQLDataException(
            "Can't convert " + toText(value) + " to " + targetType, "22018");
    }
}
