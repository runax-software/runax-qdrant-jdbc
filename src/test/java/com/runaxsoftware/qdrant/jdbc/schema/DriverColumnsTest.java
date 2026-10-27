package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

class DriverColumnsTest {

    @ParameterizedTest
    @CsvSource({
        "ID,      _id",
        "PAYLOAD, _payload",
        "VECTOR,  _vector",
        "SCORE,   _score",
        "GROUP,   _group",
        "LOOKUP,  _lookup"})
    void namesEachColumn(DriverColumns column, String name) {
        assertEquals(name, column.columnName());
    }

    @ParameterizedTest
    @EnumSource(DriverColumns.class)
    void startsEveryNameWithAnUnderscoreAndDescribesIt(DriverColumns column) {
        assertTrue(column.columnName().startsWith("_"), column.columnName());
        assertFalse(column.remarks().isBlank());
    }

    @Test
    void describesTheIdAndPayloadColumns() {
        assertEquals("Point ID: an unsigned integer or a UUID", DriverColumns.ID.remarks());
        assertEquals("The whole payload as JSON", DriverColumns.PAYLOAD.remarks());
    }

    @ParameterizedTest
    @EnumSource(DriverColumns.class)
    void reservesEachColumnName(DriverColumns column) {
        assertTrue(DriverColumns.isReserved(column.columnName()));
        assertEquals(Optional.of(column), DriverColumns.of(column.columnName()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "payload", "vector", "_ID", "_Score", "_ts", "_", "",
        " _id", "_score_vector"})
    void leavesOtherNamesToPayloadFields(String name) {
        assertFalse(DriverColumns.isReserved(name));
        assertEquals(Optional.empty(), DriverColumns.of(name));
    }
}
