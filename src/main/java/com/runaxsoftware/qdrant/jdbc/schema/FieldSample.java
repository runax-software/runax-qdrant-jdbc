package com.runaxsoftware.qdrant.jdbc.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;

import java.util.EnumSet;
import java.util.Set;

final class FieldSample {
    private final Set<ValueKind> kinds = EnumSet.noneOf(ValueKind.class);
    private PayloadIndexType indexType;

    void observe(JsonNode value) {
        ValueKind kind = ValueKind.of(value);
        if (kind != null) kinds.add(kind);
    }

    void indexedAs(PayloadIndexType indexType) {
        this.indexType = indexType;
    }

    Set<ValueKind> kinds() {
        return this.kinds;
    }

    PayloadIndexType indexType() {
        return this.indexType;
    }
}
