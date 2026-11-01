package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
                () -> statement.executeQuery("SELECT * FROM system.telemetry"));

            assertEquals("42S02", error.getSQLState());
        }
    }
}
