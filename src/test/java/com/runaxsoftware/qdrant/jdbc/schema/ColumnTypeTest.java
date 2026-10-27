package com.runaxsoftware.qdrant.jdbc.schema;

import static com.runaxsoftware.qdrant.jdbc.schema.ValueKind.ARRAY;
import static com.runaxsoftware.qdrant.jdbc.schema.ValueKind.BOOLEAN;
import static com.runaxsoftware.qdrant.jdbc.schema.ValueKind.FLOAT;
import static com.runaxsoftware.qdrant.jdbc.schema.ValueKind.INTEGER;
import static com.runaxsoftware.qdrant.jdbc.schema.ValueKind.OBJECT;
import static com.runaxsoftware.qdrant.jdbc.schema.ValueKind.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

class ColumnTypeTest {

    private static final Set<ValueKind> NOTHING = EnumSet.noneOf(ValueKind.class);

    // Payload indexes

    @ParameterizedTest
    @CsvSource({
        "KEYWORD,  TEXT",
        "TEXT,     TEXT",
        "UUID,     TEXT",
        "INTEGER,  INTEGER",
        "FLOAT,    FLOAT",
        "BOOL,     BOOLEAN",
        "DATETIME, DATETIME",
        "GEO,      JSON"})
    void typesEachPayloadIndex(PayloadIndexType index, ColumnType type) {
        assertEquals(type, ColumnType.forIndex(index));
    }

    @Test
    void letsTheSampleDecideWithoutAKnownIndex() {
        assertNull(ColumnType.forIndex(null));
        assertNull(ColumnType.forIndex(PayloadIndexType.UNKNOWN));
    }

    // Payload fields without an index

    @Test
    void typesUnindexedFieldsFromTheirValues() {
        assertEquals(ColumnType.TEXT, ColumnType.forPayloadField(null, EnumSet.of(STRING)));
        assertEquals(ColumnType.INTEGER, ColumnType.forPayloadField(null, EnumSet.of(INTEGER)));
        assertEquals(ColumnType.FLOAT, ColumnType.forPayloadField(null, EnumSet.of(FLOAT)));
        assertEquals(ColumnType.FLOAT,
            ColumnType.forPayloadField(null, EnumSet.of(INTEGER, FLOAT)));
        assertEquals(ColumnType.BOOLEAN, ColumnType.forPayloadField(null, EnumSet.of(BOOLEAN)));
    }

    @Test
    void typesAFieldWithoutValuesAsText() {
        assertEquals(ColumnType.TEXT, ColumnType.forPayloadField(null, NOTHING));
    }

    @Test
    void typesMixedValuesAsJson() {
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(null, EnumSet.of(STRING, INTEGER)));
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(null, EnumSet.of(BOOLEAN, STRING)));
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(null, EnumSet.of(BOOLEAN, INTEGER)));
    }

    @Test
    void typesObjectsAndArraysAsJson() {
        assertEquals(ColumnType.JSON, ColumnType.forPayloadField(null, EnumSet.of(OBJECT)));
        assertEquals(ColumnType.JSON, ColumnType.forPayloadField(null, EnumSet.of(ARRAY)));
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(null, EnumSet.of(STRING, ARRAY)));
    }

    // Payload fields with an index

    @Test
    void keepsTheIndexTypeWhenTheSampleFits() {
        assertEquals(ColumnType.TEXT,
            ColumnType.forPayloadField(ColumnType.TEXT, EnumSet.of(STRING)));
        assertEquals(ColumnType.INTEGER,
            ColumnType.forPayloadField(ColumnType.INTEGER, EnumSet.of(INTEGER)));
        assertEquals(ColumnType.FLOAT,
            ColumnType.forPayloadField(ColumnType.FLOAT, EnumSet.of(INTEGER)));
        assertEquals(ColumnType.FLOAT,
            ColumnType.forPayloadField(ColumnType.FLOAT, EnumSet.of(INTEGER, FLOAT)));
        assertEquals(ColumnType.BOOLEAN,
            ColumnType.forPayloadField(ColumnType.BOOLEAN, EnumSet.of(BOOLEAN)));
        assertEquals(ColumnType.DATETIME,
            ColumnType.forPayloadField(ColumnType.DATETIME, EnumSet.of(STRING)));
    }

    @Test
    void keepsTheIndexTypeWithoutASample() {
        for (ColumnType indexed : new ColumnType[]{ColumnType.TEXT, ColumnType.INTEGER,
            ColumnType.FLOAT, ColumnType.BOOLEAN, ColumnType.DATETIME, ColumnType.JSON}) {
            assertEquals(indexed, ColumnType.forPayloadField(indexed, NOTHING), indexed.name());
        }
    }

    @Test
    void widensAnIntegerIndexWithFloatsToFloat() {
        assertEquals(ColumnType.FLOAT,
            ColumnType.forPayloadField(ColumnType.INTEGER, EnumSet.of(INTEGER, FLOAT)));
        assertEquals(ColumnType.FLOAT,
            ColumnType.forPayloadField(ColumnType.INTEGER, EnumSet.of(FLOAT)));
    }

    @Test
    void fallsBackToJsonWhenTheSampleDoesNotFitTheIndex() {
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(ColumnType.TEXT, EnumSet.of(INTEGER)));
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(ColumnType.BOOLEAN, EnumSet.of(STRING)));
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(ColumnType.DATETIME, EnumSet.of(INTEGER)));
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(ColumnType.FLOAT, EnumSet.of(STRING)));
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(ColumnType.INTEGER, EnumSet.of(INTEGER, STRING)));
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(ColumnType.TEXT, EnumSet.of(ARRAY)));
    }

    @Test
    void typesGeoFieldsAsJson() {
        assertEquals(ColumnType.JSON, ColumnType.forPayloadField(
            ColumnType.forIndex(PayloadIndexType.GEO), EnumSet.of(OBJECT)));
    }

    @Test
    void letsTheSampleDecideForAnUnknownIndex() {
        assertEquals(ColumnType.TEXT, ColumnType.forPayloadField(
            ColumnType.forIndex(PayloadIndexType.UNKNOWN), EnumSet.of(STRING)));
        assertEquals(ColumnType.INTEGER, ColumnType.forPayloadField(
            ColumnType.forIndex(PayloadIndexType.UNKNOWN), EnumSet.of(INTEGER)));
    }

    @Test
    void typesAnIndexedFieldWithoutValuesFromTheIndex() {
        assertEquals(ColumnType.TEXT,
            ColumnType.forPayloadField(ColumnType.forIndex(PayloadIndexType.UUID), NOTHING));
        assertEquals(ColumnType.JSON,
            ColumnType.forPayloadField(ColumnType.forIndex(PayloadIndexType.GEO), NOTHING));
    }

    // What each type holds

    @Test
    void acceptsTheValuesEachTypeHolds() {
        assertTrue(ColumnType.TEXT.accepts(EnumSet.of(STRING)));
        assertTrue(ColumnType.INTEGER.accepts(EnumSet.of(INTEGER)));
        assertTrue(ColumnType.FLOAT.accepts(EnumSet.of(INTEGER, FLOAT)));
        assertTrue(ColumnType.BOOLEAN.accepts(EnumSet.of(BOOLEAN)));
        assertTrue(ColumnType.DATETIME.accepts(EnumSet.of(STRING)));
    }

    @Test
    void rejectsValuesATypeDoesNotHold() {
        assertFalse(ColumnType.TEXT.accepts(EnumSet.of(INTEGER)));
        assertFalse(ColumnType.INTEGER.accepts(EnumSet.of(FLOAT)));
        assertFalse(ColumnType.FLOAT.accepts(EnumSet.of(STRING)));
        assertFalse(ColumnType.BOOLEAN.accepts(EnumSet.of(STRING)));
        assertFalse(ColumnType.TEXT.accepts(EnumSet.of(STRING, OBJECT)));
    }

    @ParameterizedTest
    @EnumSource(ColumnType.class)
    void acceptsAnEmptySample(ColumnType type) {
        assertTrue(type.accepts(NOTHING));
    }

    // Vectors

    @ParameterizedTest
    @CsvSource({
        "DENSE,       VECTOR",
        "MULTIVECTOR, MULTIVECTOR",
        "SPARSE,      SPARSE_VECTOR"})
    void typesEachKindOfVector(VectorKind kind, ColumnType type) {
        assertEquals(type, ColumnType.forVector(kind));
    }
}
