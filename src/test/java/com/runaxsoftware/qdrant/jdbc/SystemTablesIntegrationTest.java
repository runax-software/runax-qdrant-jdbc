package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;
import com.runaxsoftware.qdrant.jdbc.system.SystemColumn;
import com.runaxsoftware.qdrant.jdbc.system.SystemTable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the {@code system} schema's tables from a real server, so what the driver reports about
 * a collection is what Qdrant actually answers. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class SystemTablesIntegrationTest {

    private static final String PREFIX =
        "jdbc_sys_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    private static QdrantGateway gateway;
    private static Connection connection;

    @BeforeAll
    static void createCollections() throws SQLException {
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, PREFIX);
        SampleCollections.alias(gateway, PREFIX + "current_products", PREFIX + "products");
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
    }

    @AfterAll
    static void deleteCollections() throws SQLException {
        if (connection != null) {
            connection.close();
        }

        if (gateway != null) {
            SampleCollections.deleteAliases(gateway, PREFIX);
            SampleCollections.delete(gateway, PREFIX);
            gateway.close();
        }
    }

    @Test
    void readsEveryCollectionsStateAndParameters() throws SQLException {
        List<String> described = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name, status, points_count, "
                 + "segments_count, shard_number, replication_factor, on_disk_payload, vectors "
                 + "FROM system.collections")) {
            while (rows.next()) {
                String name = rows.getString("name");
                if (!name.startsWith(PREFIX)) continue;

                described.add(name + " " + rows.getString("status"));

                assertTrue(rows.getLong("points_count") > 0, name);
                assertTrue(rows.getLong("segments_count") > 0, name);
                assertEquals(1, rows.getLong("shard_number"), name);
                assertEquals(1, rows.getLong("replication_factor"), name);
                rows.getBoolean("on_disk_payload");
                assertNotNull(rows.getString("vectors"), name);
                assertTrue(rows.getString("vectors").startsWith("{"), name);
            }
        }

        assertEquals(List.of(PREFIX + "articles green", PREFIX + "events green",
            PREFIX + "products green"), described);
    }

    @Test
    void readsThePayloadIndexesOfACollection() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT payload_indexes FROM system.collections")) {
            String indexes = null;

            while (rows.next()) {
                // One statement reads them all, so find the sample collection among them
                String row = rows.getString("payload_indexes");
                if (row != null && row.contains("\"lang\"")) indexes = row;
            }

            assertNotNull(indexes, "the sample articles have a lang index");
            assertTrue(indexes.contains("keyword"), indexes);
        }
    }

    @Test
    void readsEveryAliasAndTheCollectionItPointsAt() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT alias_name, collection_name FROM system.aliases")) {
            List<String> listed = new ArrayList<>();

            while (rows.next()) {
                if (rows.getString("alias_name").startsWith(PREFIX)) {
                    listed.add(rows.getString("alias_name") + " -> "
                        + rows.getString("collection_name"));
                }
            }

            assertEquals(List.of(PREFIX + "current_products -> " + PREFIX + "products"), listed);
        }
    }

    @Test
    void readsASystemTableWithoutAnyCollectionNamedLikeIt() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT name AS collection FROM qdrant.system.collections LIMIT 1")) {
            assertEquals("collection", rows.getMetaData().getColumnLabel(1));
            assertTrue(rows.next());
            assertNotNull(rows.getString("collection"));
            assertFalse(rows.next());
        }
    }

    @Test
    void reportsAnUnknownSystemTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> statement.executeQuery("SELECT * FROM system.queries"));

            assertEquals("42S02", error.getSQLState());
        }
    }

    // Snapshots

    /**
     * Takes a snapshot of a sample collection, so the table has a row to read, and deletes it
     * again, so the server is left as it was found.
     */
    @Test
    void readsASnapshotItTookOfACollection() throws SQLException {
        String collection = PREFIX + "products";
        String snapshot = gateway.request(HttpMethod.POST,
            "/collections/" + collection + "/snapshots", null).path("name").asText();

        try {
            assertEquals(List.of(collection + " " + snapshot), snapshots(collection));

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT collection_name, name, "
                     + "creation_time, size, checksum FROM system.snapshots")) {
                while (rows.next()) {
                    if (!snapshot.equals(rows.getString("name"))) continue;

                    assertNotNull(rows.getTimestamp("creation_time"), snapshot);
                    assertTrue(rows.getLong("size") > 0, snapshot);
                    assertEquals(64, rows.getString("checksum").length(), "a SHA-256 checksum");
                }
            }
        } finally {
            gateway.request(HttpMethod.DELETE,
                "/collections/" + collection + "/snapshots/" + snapshot, null);
        }

        assertEquals(List.of(), snapshots(collection));
    }

    // Shards and shard keys

    @Test
    void readsEveryShardOfTheSampleCollections() throws SQLException {
        List<String> described = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT collection_name, shard_id, "
                 + "shard_key, placement, peer_id, state, points_count FROM system.shards")) {
            while (rows.next()) {
                String collection = rows.getString("collection_name");
                if (!collection.startsWith(PREFIX)) continue;

                described.add(collection + " " + rows.getLong("shard_id") + " "
                    + rows.getString("placement") + " " + rows.getString("state"));

                assertNull(rows.getString("shard_key"), "the samples aren't custom-sharded");
                assertTrue(rows.getLong("peer_id") > 0, collection);
                assertTrue(rows.getLong("points_count") > 0, collection);
            }
        }

        assertEquals(List.of(PREFIX + "articles 0 local Active", PREFIX + "events 0 local Active",
            PREFIX + "products 0 local Active"), described);
    }

    /**
     * A shard key can only be created on a cluster, so a standalone server has none. The table
     * is still read, and reports none, rather than failing.
     */
    @Test
    void readsNoShardKeysFromAStandaloneServer() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT collection_name, shard_key FROM system.shard_keys")) {
            while (rows.next()) {
                assertFalse(rows.getString("collection_name").startsWith(PREFIX),
                    "a sample collection isn't custom-sharded");
            }
        }
    }

    // Cluster and peers

    @Test
    void readsOneRowAboutTheCluster() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT status, peer_id, peer_count, "
                 + "raft_term, raft_role, consensus_thread_status FROM system.cluster")) {
            assertTrue(rows.next());
            String status = rows.getString("status");

            if ("disabled".equals(status)) {
                assertNull(rows.getString("raft_role"), "a standalone server has no Raft state");
                assertEquals(0, rows.getLong("peer_count"));
                assertTrue(rows.wasNull(), "nor any peers");
            } else {
                assertEquals("enabled", status);
                assertTrue(rows.getLong("peer_id") > 0);
                assertTrue(rows.getLong("raft_term") > 0);
                assertNotNull(rows.getString("consensus_thread_status"));
            }

            assertFalse(rows.next(), "the cluster is one row");
        }
    }

    @Test
    void readsThePeersTheClusterStatusCounts() throws SQLException {
        long counted;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT peer_count FROM system.cluster")) {
            assertTrue(rows.next());
            counted = rows.getLong("peer_count");
        }

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT peer_id, uri, is_this_peer, is_leader FROM system.peers")) {
            long listed = 0;

            while (rows.next()) {
                listed++;
                assertTrue(rows.getLong("peer_id") > 0);
                assertTrue(rows.getString("uri").startsWith("http"), rows.getString("uri"));
                rows.getBoolean("is_this_peer");
                rows.getBoolean("is_leader");
            }

            assertEquals(counted, listed, "every peer the cluster status counts has a row");
        }
    }

    // Telemetry, metrics, and issues

    @Test
    void readsTheTelemetryAsOneRowPerValueInIt() throws SQLException {
        List<String> paths = new ArrayList<>();
        String version = null;

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT path, value, value_type FROM system.telemetry")) {
            while (rows.next()) {
                paths.add(rows.getString("path"));

                if ("app.version".equals(rows.getString("path"))) {
                    version = rows.getString("value");
                    assertEquals("string", rows.getString("value_type"));
                }
            }
        }

        assertEquals(connection.getMetaData().getDatabaseProductVersion(), version);
        assertTrue(paths.contains("collections.number_of_collections"), paths.toString());
        assertEquals(paths.stream().sorted().toList(), paths, "rows come sorted by path");
    }

    @Test
    void readsTheMetricsAsOneRowPerSample() throws SQLException {
        String products = PREFIX + "products";
        Double collections = null;
        Double points = null;

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT name, labels, value, type, help FROM system.metrics")) {
            while (rows.next()) {
                if ("collections_total".equals(rows.getString("name"))) {
                    collections = rows.getDouble("value");
                    assertEquals("gauge", rows.getString("type"));
                    assertNotNull(rows.getString("help"));
                    assertNull(rows.getString("labels"), "it counts them all, so it has no labels");
                }

                if ("collection_points".equals(rows.getString("name"))
                    && ("{\"id\":\"" + products + "\"}").equals(rows.getString("labels"))) {
                    points = rows.getDouble("value");
                }
            }
        }

        assertNotNull(collections, "collections_total");
        assertTrue(collections >= 3, "at least the three sample collections: " + collections);
        assertEquals(Double.valueOf(pointsCount(products)), points, "the points of " + products);
    }

    @Test
    void readsTheIssuesTheServerReports() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id, description, "
                 + "related_collection, timestamp, solution FROM system.issues")) {
            while (rows.next()) {
                assertNotNull(rows.getString("id"));
                assertNotNull(rows.getString("description"));
                assertNotNull(rows.getTimestamp("timestamp"));
            }
        }
    }

    /**
     * Every system table reads, and reports the columns and types the driver promises without
     * calling Qdrant, whether the table has rows on this server or not.
     */
    @Test
    void readsEverySystemTableWithTheColumnsAndTypesItPromises() throws SQLException {
        for (SystemTable table : SystemTable.values()) {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT * FROM system." + table.tableName() + " LIMIT 3")) {
                ResultSetMetaData columns = rows.getMetaData();
                assertEquals(table.columns().size(), columns.getColumnCount(), table.tableName());

                for (int i = 0; i < table.columns().size(); i++) {
                    SystemColumn column = table.columns().get(i);
                    String where = table.tableName() + "." + column.name();

                    assertEquals(column.name(), columns.getColumnLabel(i + 1), where);
                    assertEquals(column.type().sqlType(), columns.getColumnType(i + 1), where);
                    assertEquals(column.type().typeName(), columns.getColumnTypeName(i + 1), where);
                }

                while (rows.next()) {
                    for (int i = 1; i <= columns.getColumnCount(); i++) rows.getObject(i);
                }
            }
        }
    }

    // What system.collections says a collection holds, to check a metric about it against
    private long pointsCount(String collection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT name, points_count FROM system.collections")) {
            while (rows.next()) {
                if (collection.equals(rows.getString("name"))) return rows.getLong("points_count");
            }
        }

        throw new AssertionError("No row for " + collection);
    }

    private List<String> snapshots(String collection) throws SQLException {
        List<String> listed = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT collection_name, name FROM system.snapshots")) {
            while (rows.next()) {
                if (collection.equals(rows.getString("collection_name"))) {
                    listed.add(rows.getString("collection_name") + " " + rows.getString("name"));
                }
            }
        }

        return listed;
    }
}
