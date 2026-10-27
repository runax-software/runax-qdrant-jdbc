package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.BigInteger;

class ValueKindTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "\"Lamp\"                   | STRING",
        "\"\"                       | STRING",
        "\"2024-03-01T10:00:00Z\"   | STRING",
        "3                          | INTEGER",
        "-7                         | INTEGER",
        "9007199254740993           | INTEGER",
        "18446744073709551615       | INTEGER",
        "2.5                        | FLOAT",
        "1.0                        | FLOAT",
        "1e3                        | FLOAT",
        "true                       | BOOLEAN",
        "false                      | BOOLEAN",
        "{\"color\": \"black\"}     | OBJECT",
        "{}                         | OBJECT",
        "[1, 2]                     | ARRAY",
        "[]                         | ARRAY"})
    void namesTheKindOfAJsonValue(String json, ValueKind kind) {
        assertEquals(kind, ValueKind.of(Json.parse(json)));
    }

    @Test
    void readsNumbersOfEveryJavaType() {
        assertEquals(ValueKind.INTEGER, ValueKind.of(NODES.numberNode(3)));
        assertEquals(ValueKind.INTEGER, ValueKind.of(NODES.numberNode(3L)));
        assertEquals(ValueKind.INTEGER, ValueKind.of(NODES.numberNode(BigInteger.TEN)));
        assertEquals(ValueKind.FLOAT, ValueKind.of(NODES.numberNode(2.5f)));
        assertEquals(ValueKind.FLOAT, ValueKind.of(NODES.numberNode(new BigDecimal("2.5"))));
    }

    @Test
    void saysNothingAboutMissingOrNullValues() {
        assertNull(ValueKind.of(null));
        assertNull(ValueKind.of(NullNode.getInstance()));
        assertNull(ValueKind.of(MissingNode.getInstance()));
    }

    @Test
    void ignoresNodeTypesQdrantNeverSends() {
        assertNull(ValueKind.of(BinaryNode.valueOf(new byte[]{1, 2})));
        assertNull(ValueKind.of(NODES.pojoNode(new Object())));
    }
}
