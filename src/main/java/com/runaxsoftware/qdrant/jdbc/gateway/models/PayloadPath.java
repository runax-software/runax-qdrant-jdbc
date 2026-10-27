package com.runaxsoftware.qdrant.jdbc.gateway.models;

import com.fasterxml.jackson.databind.JsonNode;

public final class PayloadPath {
    private PayloadPath() {
    }

    /**
     * Whether a payload key points inside an object or array, such as {@code specs.color} or
     * {@code tags[]}.
     */
    public static boolean isNested(String path) {
        return path.indexOf('.') >= 0 || path.indexOf('[') >= 0;
    }

    /**
     * The value at a dotted path such as {@code specs.color}, or {@code null} when it's missing;
     * paths into arrays aren't followed.
     */
    public static JsonNode valueAt(JsonNode payload, String path) {
        if (payload == null || path.indexOf('[') >= 0) return null;

        JsonNode node = payload;
        for (String key : path.split("\\.", -1)) {
            node = node.get(key);

            if (node == null) return null;
        }

        return node;
    }
}
