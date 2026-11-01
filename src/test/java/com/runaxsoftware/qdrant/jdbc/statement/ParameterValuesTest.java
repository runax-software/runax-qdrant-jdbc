package com.runaxsoftware.qdrant.jdbc.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.VectorLiteral;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

class ParameterValuesTest {

    @Test
    void keepsTextBooleansAndNumbers() throws SQLException {
        assertEquals(new Literal("en"), ParameterValues.of("en"));
        assertEquals(new Literal(true), ParameterValues.of(true));
        assertEquals(new Literal(7L), ParameterValues.of(7));
        assertEquals(new Literal(7L), ParameterValues.of((short) 7));
        assertEquals(new Literal(7L), ParameterValues.of(7L));
        assertEquals(new Literal(0.1), ParameterValues.of(0.1f), "the float as written, not 0.10000000149");
        assertEquals(new Literal(2.5), ParameterValues.of(2.5));
        assertEquals(new Literal(null), ParameterValues.of(null));
    }

    @Test
    void readsBigNumbersAsWholeWhereTheyAre() throws SQLException {
        assertEquals(new Literal(3L), ParameterValues.of(new BigDecimal("3.00")));
        assertEquals(new Literal(3.25), ParameterValues.of(new BigDecimal("3.25")));
        assertEquals(new Literal(5L), ParameterValues.of(BigInteger.valueOf(5)));
        assertEquals("22003", assertThrows(SQLException.class,
                () -> ParameterValues.of(new BigInteger("18446744073709551616"))).getSQLState());
    }

    @Test
    void writesDateTimesAsUtcText() throws SQLException {
        Instant instant = Instant.parse("2024-03-01T10:00:00Z");

        assertEquals(new Literal("2024-03-01T10:00:00Z"), ParameterValues.of(instant));
        assertEquals(new Literal("2024-03-01T10:00:00Z"), ParameterValues.of(Timestamp.from(instant)));
        assertEquals(new Literal("2024-03-01T10:00:00Z"),
                ParameterValues.of(OffsetDateTime.of(2024, 3, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2))));
        assertEquals(new Literal("2024-03-01T00:00:00Z"), ParameterValues.of(LocalDate.of(2024, 3, 1)));
    }

    @Test
    void writesUuidsAsText() throws SQLException {
        UUID id = UUID.fromString("5c56c793-69f3-4fbf-87e6-c4bf54c28c26");

        assertEquals(new Literal(id.toString()), ParameterValues.of(id));
    }

    @Test
    void readsArraysAndListsOfNumbersAsVectors() throws SQLException {
        VectorLiteral vector = new VectorLiteral(List.of(0.1, 0.5));

        assertEquals(vector, ParameterValues.of(new float[] {0.1f, 0.5f}));
        assertEquals(vector, ParameterValues.of(new double[] {0.1, 0.5}));
        assertEquals(vector, ParameterValues.of(new Double[] {0.1, 0.5}));
        assertEquals(vector, ParameterValues.of(List.of(0.1, 0.5)));
        assertEquals("22023", assertThrows(SQLException.class,
                () -> ParameterValues.of(new double[0])).getSQLState());
    }

    @Test
    void rejectsValuesQdrantHasNoUseFor() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> ParameterValues.of(new Object()));
        assertThrows(SQLFeatureNotSupportedException.class, () -> ParameterValues.of(Time.valueOf("10:00:00")));
        assertThrows(SQLFeatureNotSupportedException.class, () -> ParameterValues.of(List.of("a")));
    }
}
