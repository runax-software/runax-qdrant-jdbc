package com.runaxsoftware.qdrant.jdbc.gateway.models;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PayloadIndexTypeTest {

    @ParameterizedTest
    @CsvSource({
        "keyword,  KEYWORD",
        "integer,  INTEGER",
        "float,    FLOAT",
        "bool,     BOOL",
        "datetime, DATETIME",
        "text,     TEXT",
        "uuid,     UUID",
        "geo,      GEO"})
    void readsQdrantsDataTypes(String name, PayloadIndexType type) {
        assertEquals(type, PayloadIndexType.fromQdrant(name));
        assertEquals(name, type.qdrantName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Keyword", "KEYWORD", "DateTime"})
    void ignoresCase(String name) {
        assertEquals(name.toLowerCase(), PayloadIndexType.fromQdrant(name).qdrantName());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"hyperdimensional", "key word", "unrecognized"})
    void readsTypesItDoesNotKnowAsUnknown(String name) {
        assertEquals(PayloadIndexType.UNKNOWN, PayloadIndexType.fromQdrant(name));
    }

    @Test
    void namesTheUnknownType() {
        assertEquals("unknown", PayloadIndexType.UNKNOWN.qdrantName());
    }

    @ParameterizedTest
    @EnumSource(PayloadIndexType.class)
    void readsBackItsOwnNames(PayloadIndexType type) {
        assertEquals(type, PayloadIndexType.fromQdrant(type.qdrantName()));
    }
}
