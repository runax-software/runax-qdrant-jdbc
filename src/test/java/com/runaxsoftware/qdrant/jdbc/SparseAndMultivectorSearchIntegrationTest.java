package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
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
 * Searches a real collection's sparse vector and multivector with a vector written out, as JSON
 * in quotes. Needs Qdrant: {@code docker compose up -d}.
 *
 * <p>The chunks: 1 of document a, with keywords 1 and 7 and rows pointing along both axes; 2 of
 * b, with keyword 7 weakly and one row along the second axis; 3 of b, with keyword 3 and one
 * row between the axes.
 */
@Tag("integration")
class SparseAndMultivectorSearchIntegrationTest {

    private String chunks;
    private QdrantGateway gateway;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void createChunks() throws SQLException {
        chunks = "jdbc_shapes_" + UUID.randomUUID().toString().substring(0, 8);
        gateway = TestQdrant.gateway();
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
        statement = connection.createStatement();

        statement.executeUpdate("CREATE TABLE " + chunks
            + " (dense VECTOR(2), colbert MULTIVECTOR(2), keywords SPARSE_VECTOR)");

        // The first multivector is two rows of whole numbers, which Qdrant takes for a sparse
        // vector's indices and values unless the driver writes them as decimals
        statement.executeUpdate("INSERT INTO " + chunks + " (_id, doc, dense, colbert, keywords) "
            + "VALUES (1, 'a', [1, 0], '[[1, 0], [0, 1]]', "
            + "'{\"indices\": [1, 7], \"values\": [0.5, 0.9]}'), "
            + "(2, 'b', [0, 1], '[[0, 1]]', '{\"indices\": [7], \"values\": [0.2]}'), "
            + "(3, 'b', [1, 1], '[[1, 1]]', '{\"indices\": [3], \"values\": [1.0]}')");
    }

    @AfterEach
    void deleteChunks() throws SQLException {
        statement.close();
        connection.close();
        gateway.deleteCollection(chunks);
        gateway.close();
    }

    @Test
    void searchesASparseVectorWithIndicesAndValues() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT _id, _score FROM " + chunks
            + " ORDER BY similarity(keywords, '{\"indices\": [7], \"values\": [1.0]}') DESC "
            + "LIMIT 10")) {
            assertTrue(rows.next());
            assertEquals("1", rows.getString(1));
            assertEquals(0.9, rows.getDouble(2), 1e-6);
            assertTrue(rows.next());
            assertEquals("2", rows.getString(1));
            assertEquals(0.2, rows.getDouble(2), 1e-6);
            assertTrue(!rows.next(), "3 shares no index with the query");
        }
    }

    @Test
    void searchesAMultivectorWithRowsOfWholeNumbers() throws SQLException {
        assertEquals(List.of("1", "3", "2"), ids("SELECT _id FROM " + chunks
            + " ORDER BY similarity(colbert, '[[1, 0], [0, 1]]') DESC LIMIT 10"));
        assertEquals(List.of("3"), ids("SELECT _id FROM " + chunks
            + " ORDER BY similarity(colbert, '[[0.7, 0.7]]') DESC LIMIT 1"));
    }

    @Test
    void searchesADenseVectorWrittenAsText() throws SQLException {
        assertEquals(List.of("2"), ids("SELECT _id FROM " + chunks
            + " ORDER BY similarity(dense, '[0, 1]') DESC LIMIT 1"));
    }

    @Test
    void filtersGroupsAndSkipsAsAnyOtherSearchDoes() throws SQLException {
        String nearest = " ORDER BY similarity(keywords, "
            + "'{\"indices\": [3, 7], \"values\": [1.0, 1.0]}') DESC";

        assertEquals(List.of("3", "1", "2"), ids("SELECT _id FROM " + chunks + nearest
            + " LIMIT 10"));
        assertEquals(List.of("3", "2"), ids("SELECT _id FROM " + chunks + " WHERE doc = 'b'"
            + nearest + " LIMIT 10"));
        assertEquals(List.of("1", "2"), ids("SELECT _id FROM " + chunks + nearest
            + " LIMIT 10 OFFSET 1"));
        assertEquals(List.of("3", "1"), ids("SELECT _id FROM " + chunks + nearest
            + " LIMIT 1 BY doc LIMIT 10"));
    }

    @Test
    void takesBothAsParameters() throws SQLException {
        try (PreparedStatement sparse = connection.prepareStatement("SELECT _id FROM " + chunks
            + " ORDER BY similarity(keywords, ?) DESC LIMIT 1")) {
            sparse.setString(1, "{\"indices\": [3], \"values\": [1.0]}");

            try (ResultSet rows = sparse.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("3", rows.getString(1));
            }
        }

        try (PreparedStatement multi = connection.prepareStatement("SELECT _id FROM " + chunks
            + " ORDER BY similarity(colbert, ?) DESC LIMIT 1")) {
            multi.setObject(1, new float[][] {{1f, 0f}, {0f, 1f}});

            try (ResultSet rows = multi.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("1", rows.getString(1));
            }
        }
    }

    @Test
    void runsSearchesOfEveryShapeInOneCall() throws SQLException {
        assertTrue(statement.execute("SELECT _id FROM " + chunks + " ORDER BY similarity("
            + "keywords, '{\"indices\": [3], \"values\": [1.0]}') DESC LIMIT 1; SELECT _id FROM "
            + chunks + " ORDER BY similarity(colbert, '[[0, 1]]') DESC LIMIT 1"));

        try (ResultSet rows = statement.getResultSet()) {
            assertTrue(rows.next());
            assertEquals("3", rows.getString(1));
        }

        assertTrue(statement.getMoreResults());

        try (ResultSet rows = statement.getResultSet()) {
            assertTrue(rows.next());
        }
    }

    @Test
    void saysHowEachIsWrittenBeforeItAsksQdrant() {
        for (String sql : List.of(
            "SELECT _id FROM " + chunks + " ORDER BY similarity(keywords, [0.1, 0.2]) DESC LIMIT 1",
            "SELECT _id FROM " + chunks + " ORDER BY similarity(keywords, "
                + "'{\"indices\": [7, 7], \"values\": [1, 2]}') DESC LIMIT 1",
            "SELECT _id FROM " + chunks + " ORDER BY similarity(colbert, [0.1, 0.2]) DESC LIMIT 1",
            "SELECT _id FROM " + chunks + " ORDER BY similarity(colbert, '[[1, 0], [1]]') DESC "
                + "LIMIT 1")) {
            assertEquals("22018", assertThrows(SQLException.class, () -> ids(sql)).getSQLState(),
                sql);
        }
    }

    private List<String> ids(String sql) throws SQLException {
        List<String> ids = new ArrayList<>();

        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getString("_id"));
        }

        return ids;
    }
}
