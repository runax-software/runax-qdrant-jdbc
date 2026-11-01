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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ranks the sample points against real Qdrant, so what the driver sends is a search Qdrant
 * runs and the scores it reports are Qdrant's own. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class SimilaritySearchIntegrationTest {

    private static final String PREFIX =
        "jdbc_search_" + UUID.randomUUID().toString().substring(0, 8) + "_";

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

    /**
     * The sample products' vectors: point 1 is {@code [0.9, 0.1, 0.1, 0.2]}, so searching with
     * it ranks point 1 first.
     */
    @Test
    void ranksTheClosestPointsFirst() throws SQLException {
        List<String> ids = new ArrayList<>();
        double previous = Double.MAX_VALUE;

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT _id, _score, name FROM "
                 + PREFIX + "products ORDER BY similarity(_vector, [0.9, 0.1, 0.1, 0.2]) "
                 + "DESC LIMIT 3")) {
            while (rows.next()) {
                ids.add(rows.getString("_id"));
                double score = rows.getDouble("_score");

                assertTrue(score <= previous, "scores come best first: " + score + " after "
                    + previous);
                assertFalse(rows.wasNull(), "every ranked point has a score");
                assertNotNull(rows.getString("name"));
                previous = score;
            }
        }

        assertEquals(3, ids.size(), "the limit");
        assertEquals("1", ids.get(0), "the point whose vector was searched with");
    }

    @Test
    void searchesANamedVectorAndReturnsItWhenAsked() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT _id, _score, dense, title FROM "
                 + PREFIX + "articles ORDER BY similarity(dense, [0.1, 0.2, 0.3, 0.4]) DESC "
                 + "LIMIT 1")) {
            assertTrue(rows.next());
            assertTrue(rows.getDouble("_score") > 0);
            assertTrue(rows.getString("dense").startsWith("["), rows.getString("dense"));
            assertNotNull(rows.getString("title"));
            assertFalse(rows.next());
        }
    }

    @Test
    void ranksTheSameCollectionThroughAnAlias() throws SQLException {
        String view = PREFIX + "current";
        SampleCollections.alias(gateway, view, PREFIX + "products");

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT t._id, t._score FROM " + view
                 + " t ORDER BY similarity(t._vector, [0.9, 0.1, 0.1, 0.2]) DESC LIMIT 1")) {
            assertTrue(rows.next());
            assertEquals("1", rows.getString("_id"));
        } finally {
            SampleCollections.deleteAliases(gateway, PREFIX);
        }
    }

    @Test
    void stopsAtTheStatementsMaximumRowsWhenTheSearchGivesNoLimit() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(2);

            try (ResultSet rows = statement.executeQuery("SELECT _id FROM " + PREFIX
                + "products ORDER BY similarity(_vector, [0.9, 0.1, 0.1, 0.2]) DESC")) {
                assertTrue(rows.next());
                assertTrue(rows.next());
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void reportsWhatQdrantWontRank() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // A vector of the wrong size, checked against the collection before anything runs
            SQLException size = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT _id FROM " + PREFIX + "products "
                    + "ORDER BY similarity(_vector, [0.9, 0.1]) DESC LIMIT 1"));
            assertEquals("42000", size.getSQLState());

            // Qdrant has no sparse vector to search with a dense one
            assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT _id FROM " + PREFIX + "articles "
                    + "ORDER BY similarity(keywords, [0.1, 0.2]) DESC LIMIT 1"));

            // And a collection that doesn't exist
            SQLException missing = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT _id FROM " + PREFIX + "nope "
                    + "ORDER BY similarity(_vector, [0.1]) DESC LIMIT 1"));
            assertEquals("42S02", missing.getSQLState());
        }
    }
}
