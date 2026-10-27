package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

class FieldSampleTest {

    @Test
    void startsWithNoValuesAndNoIndex() {
        FieldSample field = new FieldSample();

        assertTrue(field.kinds().isEmpty());
        assertNull(field.indexType());
    }

    @Test
    void collectsTheKindOfEveryValueSeenOnce() {
        FieldSample field = new FieldSample();

        field.observe(Json.node(3));
        field.observe(Json.node(2.5));
        field.observe(Json.node(4));
        field.observe(Json.node("x"));

        assertEquals(EnumSet.of(ValueKind.STRING, ValueKind.INTEGER, ValueKind.FLOAT),
            field.kinds());
    }

    @Test
    void ignoresMissingAndNullValues() {
        FieldSample field = new FieldSample();

        field.observe(null);
        field.observe(NullNode.getInstance());
        field.observe(MissingNode.getInstance());

        assertTrue(field.kinds().isEmpty());
    }

    @Test
    void remembersItsIndexType() {
        FieldSample field = new FieldSample();

        field.indexedAs(PayloadIndexType.KEYWORD);

        assertEquals(PayloadIndexType.KEYWORD, field.indexType());
    }

    @Test
    void keepsValuesAndIndexApart() {
        FieldSample field = new FieldSample();

        field.observe(Json.node(true));
        field.indexedAs(PayloadIndexType.BOOL);
        field.observe(Json.object("a", 1));

        assertEquals(EnumSet.of(ValueKind.BOOLEAN, ValueKind.OBJECT), field.kinds());
        assertEquals(PayloadIndexType.BOOL, field.indexType());
    }
}
