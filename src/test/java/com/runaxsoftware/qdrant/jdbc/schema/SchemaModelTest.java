package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.util.List;
import java.util.Map;

class SchemaModelTest {

    @ParameterizedTest
    @CsvSource({
        "TEXT,          " + Types.VARCHAR + ",                 VARCHAR",
        "INTEGER,       " + Types.BIGINT + ",                  BIGINT",
        "FLOAT,         " + Types.DOUBLE + ",                  DOUBLE",
        "BOOLEAN,       " + Types.BOOLEAN + ",                 BOOLEAN",
        "DATETIME,      " + Types.TIMESTAMP_WITH_TIMEZONE + ", TIMESTAMP WITH TIME ZONE",
        "JSON,          " + Types.VARCHAR + ",                 JSON",
        "VECTOR,        " + Types.VARCHAR + ",                 VECTOR",
        "MULTIVECTOR,   " + Types.VARCHAR + ",                 MULTIVECTOR",
        "SPARSE_VECTOR, " + Types.VARCHAR + ",                 SPARSE_VECTOR",
    })
    void mapsColumnTypesToSqlTypes(ColumnType type, int sqlType, String typeName) {
        assertEquals(sqlType, type.sqlType());
        assertEquals(typeName, type.typeName());
    }

    @Test
    void makesOnlyTheIdNotNull() {
        for (ColumnKind kind : ColumnKind.values()) {
            ColumnDefinition column = new ColumnDefinition("c", kind, ColumnType.TEXT, 0, null, "");

            assertEquals(kind != ColumnKind.ID, column.nullable(), kind.name());
        }
    }

    @Test
    void makesTheIdColumn() {
        ColumnDefinition id = ColumnDefinition.id();

        assertEquals(new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null,
            "Point ID: an unsigned integer or a UUID"), id);
        assertFalse(id.nullable());
    }

    @Test
    void makesThePayloadColumn() {
        assertEquals(new ColumnDefinition("_payload", ColumnKind.PAYLOAD, ColumnType.JSON, 0, null,
            "The whole payload as JSON"), ColumnDefinition.payload());
    }

    @Test
    void namesAPayloadFieldColumnAfterItsPath() {
        ColumnDefinition column = ColumnDefinition.payloadField("specs.color", ColumnType.TEXT,
            "Nested payload field (keyword index)");

        assertEquals("specs.color", column.name());
        assertEquals("specs.color", column.source());
        assertEquals(ColumnKind.PAYLOAD_FIELD, column.kind());
        assertEquals(ColumnType.TEXT, column.type());
        assertEquals(0, column.size());
        assertEquals("Nested payload field (keyword index)", column.remarks());
        assertTrue(column.nullable());
    }

    @Test
    void findsColumnsByExactName() {
        ColumnDefinition id = ColumnDefinition.id();
        TableSchema schema = new TableSchema(new QdrantCollection("products", 0, List.of(),
            Map.of()), List.of(id, ColumnDefinition.payload()));

        assertEquals("products", schema.name());
        assertEquals(id, schema.column("_id").orElseThrow());
        assertFalse(schema.column("_ID").isPresent());
        assertFalse(schema.column("id").isPresent());
        assertTrue(schema.column("missing").isEmpty());
    }

    @Test
    void findsTheFirstOfTwoColumnsWithTheSameName() {
        ColumnDefinition first = ColumnDefinition.payloadField("a", ColumnType.TEXT, "first");
        ColumnDefinition second = ColumnDefinition.payloadField("a", ColumnType.JSON, "second");
        TableSchema schema = new TableSchema(new QdrantCollection("t", 0, List.of(), Map.of()),
            List.of(first, second));

        assertEquals("first", schema.column("a").orElseThrow().remarks());
    }

    @Test
    void keepsNoSourceForTheIdAndPayloadColumns() {
        assertNull(ColumnDefinition.id().source());
        assertNull(ColumnDefinition.payload().source());
    }
}
