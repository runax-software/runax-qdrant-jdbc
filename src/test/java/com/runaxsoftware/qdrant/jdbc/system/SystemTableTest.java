package com.runaxsoftware.qdrant.jdbc.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

class SystemTableTest {

    @Test
    void namesItsTablesAndTheirColumns() {
        assertEquals("collections", SystemTable.COLLECTIONS.tableName());
        assertEquals("aliases", SystemTable.ALIASES.tableName());

        assertEquals(List.of("alias_name", "collection_name"), names(SystemTable.ALIASES));
        assertEquals(List.of("name", "status", "optimizer_status", "points_count",
            "indexed_vectors_count", "segments_count", "shard_number", "replication_factor",
            "write_consistency_factor", "on_disk_payload", "vectors", "sparse_vectors",
            "payload_indexes"), names(SystemTable.COLLECTIONS));
    }

    @ParameterizedTest
    @EnumSource(SystemTable.class)
    void describesEveryTableAndColumn(SystemTable table) {
        assertFalse(table.remarks().isBlank());

        for (SystemColumn column : table.columns()) {
            assertFalse(column.name().isBlank());
            assertFalse(column.remarks().isBlank(), column.name());
            assertEquals(column.name(), column.source());
            assertTrue(column.nullable(), column.name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"collections", "COLLECTIONS", "Collections"})
    void findsATableByNameInAnyCase(String name) {
        assertEquals(Optional.of(SystemTable.COLLECTIONS), SystemTable.named(name));
    }

    @Test
    void findsNoTableForOtherNames() {
        assertEquals(Optional.empty(), SystemTable.named("telemetry"));
        assertEquals(Optional.empty(), SystemTable.named(""));
        assertEquals(Optional.empty(), SystemTable.named(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POINTS_COUNT", "points_count"})
    void findsAColumnByNameInAnyCase(String name) {
        SystemColumn column = SystemTable.COLLECTIONS.column(name).orElseThrow();

        assertEquals("points_count", column.name());
        assertEquals(ColumnType.INTEGER, column.type());
    }

    @Test
    void findsNoColumnForOtherNames() {
        assertEquals(Optional.empty(), SystemTable.COLLECTIONS.column("alias_name"));
        assertEquals(Optional.empty(), SystemTable.ALIASES.column("_id"));
    }

    @Test
    void readsAnAliasedColumnFromItsOwnPlace() {
        SystemColumn name = SystemTable.COLLECTIONS.column("name").orElseThrow();
        SystemColumn renamed = name.as("collection");

        assertEquals("collection", renamed.name());
        assertEquals("name", renamed.source());
        assertEquals(name.type(), renamed.type());
        assertEquals(0, SystemTable.COLLECTIONS.indexOf(renamed));
    }

    @Test
    void placesEveryColumnWhereTheReaderPutIt() {
        List<SystemColumn> columns = SystemTable.COLLECTIONS.columns();

        for (int i = 0; i < columns.size(); i++) {
            assertEquals(i, SystemTable.COLLECTIONS.indexOf(columns.get(i)));
        }
    }

    @Test
    void rejectsAColumnOfAnotherTable() {
        SystemColumn alias = SystemTable.ALIASES.column("alias_name").orElseThrow();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> SystemTable.COLLECTIONS.indexOf(alias));

        assertTrue(error.getMessage().contains("alias_name"), error.getMessage());
        assertTrue(error.getMessage().contains("system.collections"), error.getMessage());
    }

    @Test
    void matchesItsSchemaNameInAnyCase() {
        assertEquals("system", SystemTable.SCHEMA);
        assertTrue(SystemTable.isSystemSchema("system"));
        assertTrue(SystemTable.isSystemSchema("SYSTEM"));
        assertFalse(SystemTable.isSystemSchema("collections"));
        assertFalse(SystemTable.isSystemSchema(""));
        assertFalse(SystemTable.isSystemSchema(null));
    }

    private static List<String> names(SystemTable table) {
        return table.columns().stream().map(SystemColumn::name).toList();
    }
}
