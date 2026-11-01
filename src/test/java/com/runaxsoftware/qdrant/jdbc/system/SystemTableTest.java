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

import java.util.Arrays;
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

    @Test
    void namesTheTablesThatDescribeTheServersStorageAndShards() {
        assertEquals("snapshots", SystemTable.SNAPSHOTS.tableName());
        assertEquals("shard_keys", SystemTable.SHARD_KEYS.tableName());
        assertEquals("shards", SystemTable.SHARDS.tableName());

        assertEquals(List.of("collection_name", "name", "creation_time", "size", "checksum"),
            names(SystemTable.SNAPSHOTS));
        assertEquals(List.of("collection_name", "shard_key"), names(SystemTable.SHARD_KEYS));
        assertEquals(List.of("collection_name", "shard_id", "shard_key", "placement", "peer_id",
            "state", "points_count"), names(SystemTable.SHARDS));
    }

    @Test
    void namesTheTablesThatDescribeTheCluster() {
        assertEquals("cluster", SystemTable.CLUSTER.tableName());
        assertEquals("peers", SystemTable.PEERS.tableName());

        assertEquals(List.of("status", "peer_id", "peer_count", "raft_term", "raft_commit",
            "raft_pending_operations", "raft_leader", "raft_role", "raft_is_voter",
            "consensus_thread_status", "consensus_thread_updated", "consensus_thread_error",
            "message_send_failures"), names(SystemTable.CLUSTER));
        assertEquals(List.of("peer_id", "uri", "is_this_peer", "is_leader"),
            names(SystemTable.PEERS));
    }

    @Test
    void namesTheTablesProjectedFromWhatIsntTabular() {
        assertEquals("telemetry", SystemTable.TELEMETRY.tableName());
        assertEquals("metrics", SystemTable.METRICS.tableName());
        assertEquals("issues", SystemTable.ISSUES.tableName());

        assertEquals(List.of("path", "value", "value_type"), names(SystemTable.TELEMETRY));
        assertEquals(List.of("name", "labels", "value", "type", "help"),
            names(SystemTable.METRICS));
        assertEquals(List.of("id", "description", "related_collection", "timestamp", "solution"),
            names(SystemTable.ISSUES));
    }

    @Test
    void namesEveryTableOnlyOnce() {
        assertEquals(SystemTable.values().length,
            Arrays.stream(SystemTable.values()).map(SystemTable::tableName).distinct().count());

        for (SystemTable table : SystemTable.values()) {
            assertEquals(Optional.of(table), SystemTable.named(table.tableName()));
            assertEquals(table.columns().size(),
                table.columns().stream().map(SystemColumn::name).distinct().count(),
                table.tableName());
        }
    }

    @Test
    void givesEveryColumnTheTypeOfWhatQdrantSendsInIt() {
        assertEquals(ColumnType.DATETIME, type(SystemTable.SNAPSHOTS, "creation_time"));
        assertEquals(ColumnType.INTEGER, type(SystemTable.SNAPSHOTS, "size"));
        assertEquals(ColumnType.INTEGER, type(SystemTable.SHARDS, "shard_id"));
        assertEquals(ColumnType.TEXT, type(SystemTable.SHARDS, "shard_key"));
        assertEquals(ColumnType.BOOLEAN, type(SystemTable.CLUSTER, "raft_is_voter"));
        assertEquals(ColumnType.JSON, type(SystemTable.CLUSTER, "message_send_failures"));
        assertEquals(ColumnType.BOOLEAN, type(SystemTable.PEERS, "is_leader"));
        assertEquals(ColumnType.FLOAT, type(SystemTable.METRICS, "value"));
        assertEquals(ColumnType.JSON, type(SystemTable.METRICS, "labels"));
        assertEquals(ColumnType.DATETIME, type(SystemTable.ISSUES, "timestamp"));
        assertEquals(ColumnType.JSON, type(SystemTable.ISSUES, "solution"));
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
        assertEquals(Optional.empty(), SystemTable.named("queries"));
        assertEquals(Optional.empty(), SystemTable.named("shard_key"));
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

    private static ColumnType type(SystemTable table, String column) {
        return table.column(column).orElseThrow().type();
    }
}
