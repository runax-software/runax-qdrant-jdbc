package com.runaxsoftware.qdrant.jdbc.schema;

import com.fasterxml.jackson.databind.JsonNode;

enum ValueKind {
    STRING, INTEGER, FLOAT, BOOLEAN, OBJECT, ARRAY;

    /**
     * The kind of a JSON value, or {@code null} for a null or missing value, which says nothing
     * about the field's type.
     */
    static ValueKind of(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isTextual()) return STRING;
        if (value.isIntegralNumber()) return INTEGER;
        if (value.isNumber()) return FLOAT;
        if (value.isBoolean()) return BOOLEAN;
        if (value.isObject()) return OBJECT;
        if (value.isArray()) return ARRAY;

        return null;   // binary and other node types never occur in Qdrant payloads
    }
}
