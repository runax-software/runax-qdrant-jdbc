package com.runaxsoftware.qdrant.jdbc.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Short ways to build JSON in tests. Values can be JSON nodes, strings, numbers, booleans,
 * maps, lists, arrays, or {@code null}; objects keep their keys in the order given.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /**
     * An object from alternating names and values: {@code object("name", "Lamp", "price", 39.9)}.
     */
    public static ObjectNode object(Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected names and values in pairs");
        }

        ObjectNode object = MAPPER.createObjectNode();

        for (int i = 0; i < namesAndValues.length; i += 2) {
            object.set((String) namesAndValues[i], node(namesAndValues[i + 1]));
        }

        return object;
    }

    /**
     * An array of the given values.
     */
    public static ArrayNode array(Object... values) {
        ArrayNode array = MAPPER.createArrayNode();

        for (Object value : values) {
            array.add(node(value));
        }

        return array;
    }

    /**
     * A value as a JSON node.
     */
    public static JsonNode node(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }

        if (value instanceof JsonNode node) {
            return node;
        }

        return MAPPER.valueToTree(value);
    }

    /**
     * Reads JSON text.
     */
    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not JSON: " + json, e);
        }
    }
}
