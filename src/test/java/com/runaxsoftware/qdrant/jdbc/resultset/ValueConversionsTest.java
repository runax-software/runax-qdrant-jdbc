package com.runaxsoftware.qdrant.jdbc.resultset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
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
import java.util.UUID;

class ValueConversionsTest {

    private static final OffsetDateTime MARCH_FIRST =
        OffsetDateTime.of(2024, 3, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void writesTextWithoutScientificNotation() {
        assertNull(ValueConversions.toText(null));
        assertEquals("1000", ValueConversions.toText(new BigDecimal("1E+3")));
        assertEquals("5", ValueConversions.toText(5L));
        assertEquals("2024-03-01T10:00Z", ValueConversions.toText(MARCH_FIRST));
    }

    @Test
    void readsBooleans() throws SQLException {
        assertFalse(ValueConversions.toBoolean(null));
        assertTrue(ValueConversions.toBoolean(true));
        assertTrue(ValueConversions.toBoolean(2.5));
        assertFalse(ValueConversions.toBoolean(0L));
        assertTrue(ValueConversions.toBoolean("TRUE"));
        assertFalse(ValueConversions.toBoolean(" false "));
        assertTrue(ValueConversions.toBoolean("1"));
        assertFalse(ValueConversions.toBoolean("0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "", "2"})
    void rejectsTextThatIsntABoolean(String text) {
        SQLDataException error = assertThrows(SQLDataException.class,
            () -> ValueConversions.toBoolean(text));

        assertEquals("22018", error.getSQLState());
    }

    @Test
    void readsWholeNumbersTruncatingFractions() throws SQLException {
        assertEquals(0, ValueConversions.toLong(null, "BIGINT", Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(1, ValueConversions.toLong(1.9, "BIGINT", Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(-1, ValueConversions.toLong(-1.9, "BIGINT", Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(42, ValueConversions.toLong(" 42 ", "BIGINT", Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(1, ValueConversions.toLong(true, "BIGINT", Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, ValueConversions.toLong(BigInteger.valueOf(Long.MAX_VALUE),
            "BIGINT", Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    void rejectsWholeNumbersOutOfRange() {
        SQLDataException tooBig = assertThrows(SQLDataException.class,
            () -> ValueConversions.toLong(new BigInteger("18446744073709551615"), "BIGINT",
                Long.MIN_VALUE, Long.MAX_VALUE));
        SQLDataException tooSmall = assertThrows(SQLDataException.class,
            () -> ValueConversions.toLong(-129, "TINYINT", Byte.MIN_VALUE, Byte.MAX_VALUE));

        assertEquals("22003", tooBig.getSQLState());
        assertTrue(tooBig.getMessage().contains("18446744073709551615"));
        assertTrue(tooSmall.getMessage().contains("TINYINT"));
    }

    @Test
    void rejectsWholeNumbersThatArentNumbers() {
        assertEquals("22018", assertThrows(SQLDataException.class,
            () -> ValueConversions.toLong("abc", "BIGINT", Long.MIN_VALUE, Long.MAX_VALUE))
            .getSQLState());
        assertEquals("22018", assertThrows(SQLDataException.class,
            () -> ValueConversions.toLong(Double.NaN, "BIGINT", Long.MIN_VALUE, Long.MAX_VALUE))
            .getSQLState());
    }

    @Test
    void readsDoubles() throws SQLException {
        assertEquals(0, ValueConversions.toDouble(null));
        assertEquals(1, ValueConversions.toDouble(true));
        assertEquals(0, ValueConversions.toDouble(false));
        assertEquals(2.5, ValueConversions.toDouble(" 2.5 "));
        assertEquals(7, ValueConversions.toDouble(7L));
        assertEquals("22018", assertThrows(SQLDataException.class,
            () -> ValueConversions.toDouble("seven")).getSQLState());
    }

    @Test
    void readsDecimals() throws SQLException {
        assertNull(ValueConversions.toBigDecimal(null));
        assertEquals(new BigDecimal("3"), ValueConversions.toBigDecimal((short) 3));
        assertEquals(new BigDecimal("3"), ValueConversions.toBigDecimal(3));
        assertEquals(new BigDecimal("18446744073709551615"),
            ValueConversions.toBigDecimal(new BigInteger("18446744073709551615")));
        assertEquals(new BigDecimal("1.5"), ValueConversions.toBigDecimal(1.5f));
        assertEquals(new BigDecimal("1.50"), ValueConversions.toBigDecimal("1.50"));
        assertEquals(BigDecimal.ONE, ValueConversions.toBigDecimal(true));
        BigDecimal same = new BigDecimal("9.99");
        assertSame(same, ValueConversions.toBigDecimal(same));
    }

    @Test
    void rejectsDecimalsThatArentFinite() {
        assertThrows(SQLDataException.class, () -> ValueConversions.toBigDecimal(Double.NaN));
        assertThrows(SQLDataException.class,
            () -> ValueConversions.toBigDecimal(Double.POSITIVE_INFINITY));
        assertThrows(SQLDataException.class, () -> ValueConversions.toBigDecimal("1,5"));
    }

    @Test
    void readsDateTimesInQdrantsFormats() throws SQLException {
        assertNull(ValueConversions.toOffsetDateTime(null));
        assertSame(MARCH_FIRST, ValueConversions.toOffsetDateTime(MARCH_FIRST));
        assertEquals(MARCH_FIRST,
            ValueConversions.toOffsetDateTime(Instant.parse("2024-03-01T10:00:00Z")));
        assertEquals(MARCH_FIRST, ValueConversions.toOffsetDateTime("2024-03-01T10:00:00Z"));
        assertEquals(OffsetDateTime.of(2024, 3, 1, 10, 0, 0, 0, ZoneOffset.ofHours(2)),
            ValueConversions.toOffsetDateTime("2024-03-01T10:00:00+02:00"));
        assertEquals(MARCH_FIRST, ValueConversions.toOffsetDateTime("2024-03-01T10:00:00"));
        assertEquals(MARCH_FIRST.withHour(0), ValueConversions.toOffsetDateTime("2024-03-01"));
    }

    @Test
    void rejectsTextThatIsntADateTime() {
        SQLDataException error = assertThrows(SQLDataException.class,
            () -> ValueConversions.toOffsetDateTime("March 1st"));

        assertEquals("22007", error.getSQLState());
    }

    @Test
    void convertsDateTimesToJdbcTypes() throws SQLException {
        assertEquals(Timestamp.from(MARCH_FIRST.toInstant()),
            ValueConversions.toTimestamp("2024-03-01T10:00:00Z"));
        assertEquals(Date.valueOf("2024-03-01"), ValueConversions.toDate("2024-03-01T10:00:00Z"));
        assertEquals(Time.valueOf("10:00:00"), ValueConversions.toTime("2024-03-01T10:00:00Z"));
        assertNull(ValueConversions.toTimestamp(null));
        assertNull(ValueConversions.toDate(null));
        assertNull(ValueConversions.toTime(null));
    }

    @Test
    void convertsToRequestedTypes() throws SQLException {
        assertNull(ValueConversions.toType(null, String.class));
        assertEquals(5L, ValueConversions.toType(5L, Number.class));
        assertEquals("5", ValueConversions.toType(5L, String.class));
        assertEquals(true, ValueConversions.toType("true", Boolean.class));
        assertEquals(5L, ValueConversions.toType("5", Long.class));
        assertEquals(5, ValueConversions.toType(5L, Integer.class));
        assertEquals((short) 5, ValueConversions.toType(5L, Short.class));
        assertEquals(2.5, ValueConversions.toType("2.5", Double.class));
        assertEquals(2.5f, ValueConversions.toType(2.5, Float.class));
        assertEquals(new BigDecimal("5"), ValueConversions.toType(5L, BigDecimal.class));

        String text = "2024-03-01T10:00:00Z";
        assertEquals(MARCH_FIRST, ValueConversions.toType(text, OffsetDateTime.class));
        assertEquals(MARCH_FIRST.toInstant(), ValueConversions.toType(text, Instant.class));
        assertEquals(LocalDateTime.of(2024, 3, 1, 10, 0),
            ValueConversions.toType(text, LocalDateTime.class));
        assertEquals(LocalDate.of(2024, 3, 1), ValueConversions.toType(text, LocalDate.class));
        assertEquals(LocalTime.of(10, 0), ValueConversions.toType(text, LocalTime.class));
        assertEquals(Timestamp.from(MARCH_FIRST.toInstant()),
            ValueConversions.toType(text, Timestamp.class));
        assertEquals(Date.valueOf("2024-03-01"), ValueConversions.toType(text, Date.class));
        assertEquals(Time.valueOf("10:00:00"), ValueConversions.toType(text, Time.class));
    }

    @Test
    void rejectsIntegerConversionsOutOfRange() {
        assertThrows(SQLDataException.class,
            () -> ValueConversions.toType(Long.MAX_VALUE, Integer.class));
        assertThrows(SQLDataException.class,
            () -> ValueConversions.toType(40_000L, Short.class));
    }

    @Test
    void rejectsTypesItCantProduce() {
        SQLDataException error = assertThrows(SQLDataException.class,
            () -> ValueConversions.toType("x", UUID.class));

        assertTrue(error.getMessage().contains("java.util.UUID"));
    }
}
