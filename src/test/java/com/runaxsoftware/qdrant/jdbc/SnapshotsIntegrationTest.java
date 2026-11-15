package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.connection.Setting;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.Json;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Runs the snapshot procedures against Qdrant: taking, listing, downloading, uploading,
 * recovering and deleting snapshots of a collection, of one shard, and of the whole storage.
 * Every snapshot a test takes is of a collection it made, or is deleted by name before the test
 * ends. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class SnapshotsIntegrationTest {

    private static final int POINTS = 3;

    @TempDir
    Path folder;

    private String collection;
    private String copy;
    private QdrantGateway gateway;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void createCollection() throws SQLException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        collection = "jdbc_snapshots_" + suffix;
        copy = "jdbc_snapshots_copy_" + suffix;
        gateway = TestQdrant.gateway();

        Properties properties = TestQdrant.properties();
        properties.setProperty(Setting.LOCAL_FILES.key(), "true");
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), properties);
        statement = connection.createStatement();

        gateway.createCollection(collection, Json.object(
            "vectors", Json.object("size", 2, "distance", "Cosine")));
        gateway.request(HttpMethod.PUT, "/collections/" + collection + "/points?wait=true",
            Json.object("points", Json.array(
                Json.object("id", 1, "vector", Json.array(0.1, 0.9), "payload",
                    Json.object("lang", "en")),
                Json.object("id", 2, "vector", Json.array(0.8, 0.2), "payload",
                    Json.object("lang", "de")),
                Json.object("id", 3, "vector", Json.array(0.5, 0.5), "payload",
                    Json.object("lang", "en")))));
    }

    @AfterEach
    void deleteCollections() throws SQLException {
        statement.close();
        connection.close();

        for (String name : List.of(collection, copy)) {
            if (!gateway.collectionExists(name)) continue;

            for (JsonNode snapshot : gateway.request(HttpMethod.GET,
                "/collections/" + name + "/snapshots", null)) {
                gateway.run(HttpMethod.DELETE, "/collections/" + name + "/snapshots/"
                    + snapshot.path("name").asText(), null);
            }
            for (JsonNode snapshot : gateway.request(HttpMethod.GET,
                "/collections/" + name + "/shards/0/snapshots", null)) {
                gateway.run(HttpMethod.DELETE, "/collections/" + name + "/shards/0/snapshots/"
                    + snapshot.path("name").asText(), null);
            }

            gateway.deleteCollection(name);
        }

        gateway.close();
    }

    @Test
    void takesListsAndDeletesASnapshotOfACollection() throws SQLException {
        assertEquals(List.of(), snapshots(collection));

        assertEquals(0, statement.executeUpdate("CALL create_snapshot('" + collection + "')"));

        List<String> taken = snapshots(collection);
        assertEquals(1, taken.size());
        assertTrue(taken.get(0).startsWith(collection), taken.get(0));

        assertEquals(0, statement.executeUpdate("CALL qdrant.delete_snapshot('" + collection
            + "', '" + taken.get(0) + "', wait => true)"));
        assertEquals(List.of(), snapshots(collection));
    }

    @Test
    void returnsAtOnceWhenToldNotToWait() throws Exception {
        // Qdrant answers that it accepted the work, with no result at all
        assertEquals(0, statement.executeUpdate(
            "CALL create_snapshot('" + collection + "', wait => false)"));

        // The snapshot turns up when Qdrant has taken it, and is then there to clean up
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (snapshots(collection).isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "the snapshot never turned up");
            Thread.sleep(100);
        }
    }

    @Test
    void downloadsASnapshotAndUploadsItAsAnotherCollection() throws Exception {
        statement.executeUpdate("CALL create_snapshot('" + collection + "')");
        String snapshot = snapshots(collection).get(0);
        Path file = folder.resolve("collection.snapshot");

        assertEquals(0, statement.executeUpdate("CALL download_snapshot('" + collection + "', '"
            + snapshot + "', '" + file + "')"));
        assertEquals(size(collection, snapshot), Files.size(file));

        assertEquals(0, statement.executeUpdate("CALL upload_snapshot('" + copy + "', '" + file
            + "', priority => 'snapshot')"));

        assertEquals(POINTS, count(copy));
        try (ResultSet rows = statement.executeQuery(
            "SELECT lang FROM " + copy + " WHERE _id = 2")) {
            assertTrue(rows.next(), "the new collection's columns are read afresh");
            assertEquals("de", rows.getString("lang"));
        }
    }

    @Test
    void takesAFilesPathAsAParameter() throws Exception {
        statement.executeUpdate("CALL create_snapshot('" + collection + "')");
        Path file = folder.resolve("by parameter.snapshot");

        try (PreparedStatement download = connection.prepareStatement(
            "CALL download_snapshot(?, ?, path => ?)")) {
            download.setString(1, collection);
            download.setString(2, snapshots(collection).get(0));
            download.setString(3, file.toString());

            assertEquals(0, download.executeUpdate());
        }

        assertTrue(Files.size(file) > 0);
    }

    @Test
    void neverOverwritesAFileAndSavesNoneForASnapshotThatIsntThere() throws Exception {
        statement.executeUpdate("CALL create_snapshot('" + collection + "')");
        String snapshot = snapshots(collection).get(0);
        Path taken = Files.writeString(folder.resolve("taken.snapshot"), "mine");
        Path missing = folder.resolve("missing.snapshot");

        SQLException exists = assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CALL download_snapshot('" + collection + "', '" + snapshot + "', '" + taken + "')"));
        assertEquals("58030", exists.getSQLState());
        assertEquals("mine", Files.readString(taken));

        SQLException notFound = assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CALL download_snapshot('" + collection + "', 'nope.snapshot', '" + missing + "')"));
        assertEquals("42S02", notFound.getSQLState());
        assertEquals("Qdrant: Not found: Snapshot nope.snapshot not found", notFound.getMessage());
        assertFalse(Files.exists(missing));

        SQLException noFile = assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CALL upload_snapshot('" + copy + "', '" + missing + "')"));
        assertEquals("58030", noFile.getSQLState());
    }

    @Test
    void reportsWhatQdrantSaysAboutAFileThatIsntASnapshot() throws Exception {
        Path file = Files.writeString(folder.resolve("not.snapshot"), "not a snapshot");

        SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CALL upload_snapshot('" + copy + "', '" + file + "')"));

        assertTrue(error.getMessage().startsWith("Qdrant: "), error.getMessage());
        assertFalse(gateway.collectionExists(copy));
    }

    @Test
    void recoversACollectionFromASnapshotQdrantFetchesItself() throws SQLException {
        statement.executeUpdate("CALL create_snapshot('" + collection + "')");
        String location = gateway.baseUrl() + "/collections/" + collection + "/snapshots/"
            + snapshots(collection).get(0);
        String apiKey = TestQdrant.properties().getProperty(Setting.PASSWORD.key());

        try (PreparedStatement recover = connection.prepareStatement(
            "CALL recover_snapshot(?, ?, priority => 'snapshot', api_key => ?)")) {
            recover.setString(1, copy);
            recover.setString(2, location);
            recover.setString(3, apiKey);

            assertEquals(0, recover.executeUpdate());
        }

        assertEquals(POINTS, count(copy));
    }

    @Test
    void takesListsMovesAndDeletesTheSnapshotsOfAShard() throws Exception {
        assertEquals(0, statement.executeUpdate(
            "CALL create_shard_snapshot('" + collection + "', 0)"));

        List<String> taken = shardSnapshots(collection);
        assertEquals(1, taken.size());
        String snapshot = taken.get(0);

        Path saved = folder.resolve("shard.snapshot");
        Path streamed = folder.resolve("streamed.snapshot");
        statement.executeUpdate("CALL download_shard_snapshot('" + collection + "', 0, '"
            + snapshot + "', '" + saved + "')");
        statement.executeUpdate("CALL stream_shard_snapshot('" + collection + "', 0, '"
            + streamed + "')");
        assertTrue(Files.size(saved) > 0);
        assertTrue(Files.size(streamed) > 0);
        assertEquals(taken, shardSnapshots(collection), "streaming one keeps none on the server");

        // Change the shard, then put each snapshot back and find it as it was
        statement.executeUpdate("DELETE FROM " + collection + " WHERE _id = 1");
        assertEquals(POINTS - 1, count(collection));
        statement.executeUpdate("CALL upload_shard_snapshot('" + collection + "', 0, '" + saved
            + "', priority => 'snapshot')");
        assertEquals(POINTS, count(collection));

        statement.executeUpdate("DELETE FROM " + collection + " WHERE _id = 2");
        statement.executeUpdate("CALL upload_shard_snapshot('" + collection + "', 0, '"
            + streamed + "')");
        assertEquals(POINTS, count(collection));

        statement.executeUpdate("DELETE FROM " + collection + " WHERE _id = 3");
        statement.executeUpdate("CALL recover_shard_snapshot('" + collection + "', 0, '"
            + snapshot + "')");
        assertEquals(POINTS, count(collection));

        assertEquals(0, statement.executeUpdate("CALL delete_shard_snapshot('" + collection
            + "', 0, '" + snapshot + "')"));
        assertEquals(List.of(), shardSnapshots(collection));
    }

    @Test
    void takesDownloadsAndDeletesASnapshotOfTheWholeStorage() throws Exception {
        List<String> before = snapshots(null);

        assertEquals(0, statement.executeUpdate("CALL create_full_snapshot()"));

        List<String> taken = new ArrayList<>(snapshots(null));
        taken.removeAll(before);
        assertEquals(1, taken.size());
        String snapshot = taken.get(0);

        try {
            Path file = folder.resolve("full.snapshot");
            statement.executeUpdate("CALL download_full_snapshot('" + snapshot + "', '" + file
                + "')");
            assertEquals(size(null, snapshot), Files.size(file));
        } finally {
            assertEquals(0, statement.executeUpdate(
                "CALL delete_full_snapshot('" + snapshot + "')"));
        }

        assertEquals(before, snapshots(null));
    }

    @Test
    void movesNoFileOnAConnectionThatDoesntAllowIt() throws Exception {
        Path file = folder.resolve("refused.snapshot");

        try (Connection plain = DriverManager.getConnection(TestQdrant.jdbcUrl(),
            TestQdrant.properties());
             Statement refused = plain.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> refused.executeUpdate(
                "CALL stream_shard_snapshot('" + collection + "', 0, '" + file + "')"));

            assertEquals("42501", error.getSQLState());
            assertFalse(Files.exists(file));

            // What moves no file needs no permission to
            assertEquals(0, refused.executeUpdate("CALL create_snapshot('" + collection + "')"));
        }
    }

    @Test
    void takesNoSnapshotOnAReadOnlyConnection() throws SQLException {
        connection.setReadOnly(true);

        SQLException error = assertThrows(SQLException.class,
            () -> statement.executeUpdate("CALL create_snapshot('" + collection + "')"));

        assertEquals("25006", error.getSQLState());
        connection.setReadOnly(false);
        assertEquals(List.of(), snapshots(collection));
    }

    // The names system.snapshots lists for a collection, or for the whole storage when null
    private List<String> snapshots(String of) throws SQLException {
        List<String> names = new ArrayList<>();

        try (ResultSet rows = statement.executeQuery(
            "SELECT collection_name, name FROM system.snapshots")) {
            while (rows.next()) {
                String owner = rows.getString("collection_name");
                if (of == null ? owner == null : of.equals(owner)) names.add(rows.getString("name"));
            }
        }

        return names;
    }

    private List<String> shardSnapshots(String of) throws SQLException {
        List<String> names = new ArrayList<>();

        try (ResultSet rows = statement.executeQuery(
            "SELECT collection_name, shard_id, name FROM system.shard_snapshots")) {
            while (rows.next()) {
                if (!of.equals(rows.getString("collection_name"))) continue;

                assertEquals(0, rows.getLong("shard_id"));
                names.add(rows.getString("name"));
            }
        }

        return names;
    }

    private long size(String of, String snapshot) throws SQLException {
        try (ResultSet rows = statement.executeQuery(
            "SELECT collection_name, name, size FROM system.snapshots")) {
            while (rows.next()) {
                String owner = rows.getString("collection_name");

                if ((of == null ? owner == null : of.equals(owner))
                    && snapshot.equals(rows.getString("name"))) {
                    return rows.getLong("size");
                }
            }
        }

        throw new AssertionError("No snapshot " + snapshot);
    }

    private long count(String table) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
