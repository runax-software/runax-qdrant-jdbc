package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.client.CollectionInfo;

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
    void findsColumnsByExactName() {
        ColumnDefinition id = new ColumnDefinition("id", ColumnKind.ID, ColumnType.TEXT, 0, null, "");
        TableSchema schema = new TableSchema(new CollectionInfo("products", 0, List.of(), Map.of()),
            List.of(id));

        assertEquals("products", schema.name());
        assertEquals(id, schema.column("id").orElseThrow());
        assertFalse(schema.column("ID").isPresent());
        assertTrue(schema.column("missing").isEmpty());
    }
}
