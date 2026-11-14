package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.Json;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs the cluster procedures against a Qdrant that is a cluster, which is the only kind that
 * takes them: the third server of {@code compose.yml}, a cluster of one node. One node is enough
 * to create and delete shard keys for real; an operation that needs a second peer is checked as
 * far as Qdrant's own answer about the peer that isn't there, which shows the request arrived
 * in the shape Qdrant reads. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class ClusterIntegrationTest {

    private String collection;
    private QdrantGateway gateway;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void createCollection() throws SQLException {
        collection = "jdbc_cluster_" + UUID.randomUUID().toString().substring(0, 8);
        gateway = TestQdrant.clusterGateway();
        connection = DriverManager.getConnection(TestQdrant.clusterJdbcUrl());
        statement = connection.createStatement();

        // Shard keys are for a collection sharded by hand, which CREATE TABLE can't ask for yet
        gateway.createCollection(collection, Json.object(
            "vectors", Json.object("size", 2, "distance", "Cosine"),
            "sharding_method", "custom"));
    }

    @AfterEach
    void deleteCollection() throws SQLException {
        statement.close();
        connection.close();
        gateway.deleteCollection(collection);
        gateway.close();
    }

    @Test
    void createsAndDeletesShardKeys() throws SQLException {
        assertEquals(0, statement.executeUpdate(
            "CALL qdrant.create_shard_key('" + collection + "', 'eu')"));
        assertEquals(0, statement.executeUpdate("CALL create_shard_key('" + collection
            + "', 7, shards_number => 2, replication_factor => 1)"));

        assertEquals(List.of("7", "eu"), shardKeys());
        assertEquals(3, shards(), "one shard for eu and two for 7");

        assertEquals(0, statement.executeUpdate(
            "CALL delete_shard_key('" + collection + "', 'eu')"));
        assertEquals(List.of("7"), shardKeys());
    }

    @Test
    void takesAProceduresArgumentsAsParameters() throws SQLException {
        try (PreparedStatement create = connection.prepareStatement(
            "CALL create_shard_key(?, ?, shards_number => ?)")) {
            assertEquals(3, create.getParameterMetaData().getParameterCount());

            create.setString(1, collection);
            create.setString(2, "us");
            create.setLong(3, 1);
            assertEquals(0, create.executeUpdate());
        }

        assertEquals(List.of("us"), shardKeys());
    }

    @Test
    void reportsWhatQdrantSaysAboutAShardKey() throws SQLException {
        statement.executeUpdate("CALL create_shard_key('" + collection + "', 'eu')");

        SQLException taken = assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CALL create_shard_key('" + collection + "', 'eu')"));
        assertTrue(taken.getMessage().contains("already exists"), taken.getMessage());

        SQLException missing = assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CALL delete_shard_key('" + collection + "', 'nope')"));
        assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());

        assertEquals("42S02", assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CALL create_shard_key('" + collection + "_gone', 'eu')")).getSQLState());
    }

    @Test
    void sendsAClusterOperationInTheShapeQdrantReads() throws SQLException {
        statement.executeUpdate("CALL create_shard_key('" + collection + "', 'eu')");
        long peer = gateway.request(com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod.GET,
            "/cluster", null).path("peer_id").asLong();

        // There is no second peer to move a shard to, and Qdrant says so about the one named
        for (String call : List.of(
            "CALL move_shard('" + collection + "', 1, " + peer + ", 424242)",
            "CALL replicate_shard('" + collection + "', 1, " + peer + ", 424242, "
                + "method => 'stream_records')",
            "CALL drop_replica('" + collection + "', 1, 424242)")) {
            SQLException error = assertThrows(SQLException.class,
                () -> statement.executeUpdate(call), call);

            assertTrue(error.getMessage().startsWith("Qdrant: "), call + ": " + error.getMessage());
            assertTrue(!error.getMessage().contains("Format error")
                && !error.getMessage().contains("did not match"), call + ": " + error.getMessage());
        }
    }

    /**
     * Qdrant answers the removal of a peer it doesn't have as done, so this only shows the
     * request is one it takes; removing a real peer needs a second one to remove.
     */
    @Test
    void asksQdrantToRemoveAPeer() throws SQLException {
        assertEquals(0, statement.executeUpdate("CALL remove_peer(424242, force => false)"));
    }

    @Test
    void refusesAProcedureOnAReadOnlyConnection() throws SQLException {
        connection.setReadOnly(true);

        assertEquals("25006", assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CALL create_shard_key('" + collection + "', 'eu')")).getSQLState());
        assertEquals(List.of(), shardKeys());
    }

    @Test
    void readsTheClustersTelemetry() throws SQLException {
        List<String> paths = new ArrayList<>();

        try (ResultSet rows = statement.executeQuery(
            "SELECT path, value FROM system.cluster_telemetry")) {
            while (rows.next()) {
                paths.add(rows.getString("path"));
                if (rows.getString("path").equals("cluster.number_of_peers")) {
                    assertEquals("1", rows.getString("value"));
                }
            }
        }

        assertTrue(paths.contains("cluster.number_of_peers"), paths.toString());
    }

    @Test
    void listsItsProceduresInTheMetadata() throws SQLException {
        List<String> names = new ArrayList<>();

        try (ResultSet procedures = connection.getMetaData().getProcedures(null, null, "%shard_key")) {
            while (procedures.next()) names.add(procedures.getString("PROCEDURE_NAME"));
        }

        assertEquals(List.of("create_shard_key", "delete_shard_key"), names);
    }

    private List<String> shardKeys() throws SQLException {
        List<String> keys = new ArrayList<>();

        try (ResultSet rows = statement.executeQuery(
            "SELECT collection_name, shard_key FROM system.shard_keys")) {
            while (rows.next()) {
                if (collection.equals(rows.getString("collection_name"))) {
                    keys.add(rows.getString("shard_key"));
                }
            }
        }

        return keys;
    }

    private int shards() throws SQLException {
        int count = 0;

        try (ResultSet rows = statement.executeQuery("SELECT collection_name FROM system.shards")) {
            while (rows.next()) {
                if (collection.equals(rows.getString("collection_name"))) count++;
            }
        }

        return count;
    }
}
