package com.runaxsoftware.qdrant.jdbc.gateway.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PayloadPathTest {

    private static final JsonNode PAYLOAD = Json.parse(
        "{\"name\": \"Lamp\", \"specs\": {\"color\": \"black\", \"size\": {\"height\": 40}},"
            + " \"tags\": [\"a\", \"b\"], \"variants\": [{\"sku\": \"A1\"}], \"note\": null,"
            + " \"\": \"empty key\"}");

    @ParameterizedTest
    @ValueSource(strings = {"specs.color", "tags[]", "variants[].sku", "a.b.c", "a[0]"})
    void findsNestedPaths(String path) {
        assertTrue(PayloadPath.isNested(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "id", "_ts", "", "key with spaces"})
    void findsTopLevelKeys(String path) {
        assertFalse(PayloadPath.isNested(path));
    }

    @Test
    void readsTopLevelAndNestedValues() {
        assertEquals("Lamp", PayloadPath.valueAt(PAYLOAD, "name").asText());
        assertEquals("black", PayloadPath.valueAt(PAYLOAD, "specs.color").asText());
        assertEquals(40, PayloadPath.valueAt(PAYLOAD, "specs.size.height").asInt());
        assertEquals(Json.object("height", 40), PayloadPath.valueAt(PAYLOAD, "specs.size"));
    }

    @Test
    void returnsNullForMissingValues() {
        assertNull(PayloadPath.valueAt(PAYLOAD, "missing"));
        assertNull(PayloadPath.valueAt(PAYLOAD, "specs.missing"));
        assertNull(PayloadPath.valueAt(PAYLOAD, "missing.color"));
        assertNull(PayloadPath.valueAt(PAYLOAD, "name.first"));
    }

    @Test
    void keepsAJsonNullApartFromAMissingValue() {
        assertTrue(PayloadPath.valueAt(PAYLOAD, "note").isNull());
    }

    @Test
    void doesNotFollowPathsIntoArrays() {
        assertNull(PayloadPath.valueAt(PAYLOAD, "tags[]"));
        assertNull(PayloadPath.valueAt(PAYLOAD, "variants[].sku"));
    }

    @Test
    void returnsNullForAMissingPayload() {
        assertNull(PayloadPath.valueAt(null, "name"));
    }

    @Test
    void readsAnEmptyKey() {
        assertEquals("empty key", PayloadPath.valueAt(PAYLOAD, "").asText());
    }
}
