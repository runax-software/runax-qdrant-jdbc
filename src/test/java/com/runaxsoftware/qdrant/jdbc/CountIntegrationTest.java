package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.Json;
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
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

/**
 * Counts the sample points against real Qdrant, so the number is Qdrant's own count of a
 * collection and not something the driver worked out from the points it read. Needs Qdrant:
 * {@code docker compose up -d}.
 */
@Tag("integration")
class CountIntegrationTest {

    private static final String PREFIX =
        "jdbc_count_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    private static QdrantGateway gateway;
    private static Connection connection;

    @BeforeAll
    static void createCollections() throws SQLException {
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, PREFIX);
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
    }

    @AfterAll
    static void deleteCollections() throws SQLException {
        if (connection != null) {
            connection.close();
        }

        if (gateway != null) {
            SampleCollections.delete(gateway, PREFIX);
            gateway.close();
        }
    }

    @Test
    void countsThePointsOfEachCollection() throws SQLException {
        assertEquals(5L, count("SELECT COUNT(*) FROM " + PREFIX + "products"));
        assertEquals(3L, count("SELECT COUNT(*) FROM " + PREFIX + "articles"));
        assertEquals(4L, count("SELECT COUNT(*) FROM " + PREFIX + "events"));
    }

    @Test
    void describesTheCountAsABigint() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) AS total FROM " + PREFIX
                 + "products")) {
            assertEquals(1, rows.getMetaData().getColumnCount());
            assertEquals("total", rows.getMetaData().getColumnLabel(1));
            assertEquals(Types.BIGINT, rows.getMetaData().getColumnType(1));

            assertTrue(rows.next());
            assertEquals(5L, rows.getLong("total"));
            assertFalse(rows.wasNull());
            assertFalse(rows.next(), "a count is one row");
        }
    }

    @Test
    void countsTheSameCollectionHoweverItIsNamed() throws SQLException {
        String view = PREFIX + "current";
        SampleCollections.alias(gateway, view, PREFIX + "products");

        try {
            assertEquals(5L, count("select count(*) from " + PREFIX + "products t"));
            assertEquals(5L, count("SELECT COUNT(*) FROM collections." + PREFIX + "products"));
            assertEquals(5L, count("SELECT COUNT(*) FROM qdrant.collections." + PREFIX
                + "products t"));
            assertEquals(5L, count("SELECT COUNT(*) FROM " + view), "counted through an alias");
        } finally {
            SampleCollections.deleteAliases(gateway, PREFIX);
        }
    }

    /**
     * A collection of its own, so what this writes doesn't change what the other tests count.
     * An empty collection also has no payload field to find a column from, which a count needs
     * none of.
     */
    @Test
    void countsWhatACollectionHoldsNow() throws SQLException {
        String collection = PREFIX + "changing";
        gateway.request(HttpMethod.PUT, "/collections/" + collection,
            Json.object("vectors", Json.object("size", 2, "distance", "Cosine")));

        try {
            assertEquals(0L, count("SELECT COUNT(*) FROM " + collection),
                "a collection without points");

            gateway.request(HttpMethod.PUT, "/collections/" + collection + "/points?wait=true",
                Json.object("points", Json.array(
                    Json.object("id", 1, "vector", Json.array(0.1, 0.2)),
                    Json.object("id", 2, "vector", Json.array(0.3, 0.4)))));

            assertEquals(2L, count("SELECT COUNT(*) FROM " + collection),
                "the count is exact, so points just written are in it");
        } finally {
            gateway.request(HttpMethod.DELETE, "/collections/" + collection, null);
        }
    }

    @Test
    void returnsNoRowForALimitOfNoRows() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + PREFIX
                 + "products LIMIT 0")) {
            assertFalse(rows.next());
        }
    }

    @Test
    void reportsWhatQdrantWontCount() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // A collection that doesn't exist, which the count call itself reports
            SQLException missing = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT COUNT(*) FROM " + PREFIX + "nope"));
            assertEquals("42S02", missing.getSQLState());

            // And the counts Qdrant has no API for, reported before anything runs
            List<String> rejected = List.of(
                "SELECT COUNT(name) FROM " + PREFIX + "products",
                "SELECT COUNT(DISTINCT name) FROM " + PREFIX + "products",
                "SELECT COUNT(*), name FROM " + PREFIX + "products",
                "SELECT name, COUNT(*) FROM " + PREFIX + "products GROUP BY name",
                "SELECT COUNT(*) FROM system.collections",
                "SELECT COUNT(*) FROM " + PREFIX + "products WHERE price > 100");

            for (String sql : rejected) {
                assertThrows(SQLException.class, () -> statement.executeQuery(sql), sql);
            }
        }
    }

    private static long count(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);

            return rows.getLong(1);
        }
    }
}
