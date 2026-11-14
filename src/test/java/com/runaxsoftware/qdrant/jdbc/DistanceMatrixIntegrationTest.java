package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads distance matrices of a real collection through {@code distance_matrix(...)}, as pairs
 * and as positions, which come from two of Qdrant's routes. Needs Qdrant:
 * {@code docker compose up -d}.
 *
 * <p>Four points: 1 and 2 are close to each other, 3 is far from both, and 4, the only one in
 * German, sits between.
 */
@Tag("integration")
class DistanceMatrixIntegrationTest {

    private String docs;
    private QdrantGateway gateway;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void createDocs() throws SQLException {
        docs = "jdbc_matrix_" + UUID.randomUUID().toString().substring(0, 8);
        gateway = TestQdrant.gateway();
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
        statement = connection.createStatement();

        statement.executeUpdate("CREATE TABLE " + docs
            + " (dense VECTOR(2), keywords SPARSE_VECTOR)");
        statement.executeUpdate("INSERT INTO " + docs + " (_id, lang, dense) VALUES "
            + "(1, 'en', [1, 0]), (2, 'en', [0.9, 0.1]), (3, 'en', [0, 1]), (4, 'de', [0.5, 0.5])");
    }

    @AfterEach
    void deleteDocs() throws SQLException {
        statement.close();
        connection.close();
        gateway.deleteCollection(docs);
        gateway.close();
    }

    @Test
    void readsEachPointsNearestNeighboursAsPairs() throws SQLException {
        Map<String, List<String>> neighbours = new HashMap<>();

        try (ResultSet rows = statement.executeQuery(
            "SELECT * FROM distance_matrix(" + docs + ", dense, 10, 1)")) {
            assertEquals(3, rows.getMetaData().getColumnCount());
            assertEquals("_a", rows.getMetaData().getColumnLabel(1));
            assertEquals("_score", rows.getMetaData().getColumnLabel(3));

            while (rows.next()) {
                neighbours.computeIfAbsent(rows.getString("_a"), x -> new ArrayList<>())
                    .add(rows.getString("_b"));
                assertTrue(rows.getDouble("_score") > 0);
            }
        }

        assertEquals(Set.of("1", "2", "3", "4"), neighbours.keySet());
        assertEquals(List.of("2"), neighbours.get("1"));
        assertEquals(List.of("1"), neighbours.get("2"));
        assertEquals(List.of("4"), neighbours.get("3"), "the point between is the closest to 3");
    }

    @Test
    void readsTheSameMatrixAsPositionsWhenAStatementNamesOne() throws SQLException {
        Map<Long, String> ids = new HashMap<>();
        int cells = 0;

        try (ResultSet rows = statement.executeQuery("SELECT _row, _col, _a, _b, _score FROM "
            + "distance_matrix(" + docs + ", dense, 10, 2)")) {
            while (rows.next()) {
                long row = rows.getLong("_row");
                long col = rows.getLong("_col");
                assertTrue(row >= 0 && row < 4 && col >= 0 && col < 4 && row != col);

                // A position names one point, whichever side of a pair it's on
                assertEquals(rows.getString("_a"), ids.computeIfAbsent(row, x -> rowsId(rows, "_a")));
                assertEquals(rows.getString("_b"), ids.computeIfAbsent(col, x -> rowsId(rows, "_b")));
                cells++;
            }
        }

        assertEquals(8, cells, "four points, two neighbours each");
        assertEquals(4, ids.size());
    }

    @Test
    void samplesOnlyThePointsTheWhereMatches() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT _a, _b FROM distance_matrix(" + docs
            + ", dense, 10, 3) WHERE lang = 'en'")) {
            int pairs = 0;

            while (rows.next()) {
                assertFalse(rows.getString("_a").equals("4") || rows.getString("_b").equals("4"),
                    "the German point isn't sampled");
                pairs++;
            }

            assertEquals(6, pairs, "three points, each with the two others");
        }

        try (PreparedStatement prepared = connection.prepareStatement(
            "SELECT _a FROM distance_matrix(" + docs + ", dense) WHERE lang = ? LIMIT 2 OFFSET 1")) {
            prepared.setString(1, "en");

            try (ResultSet rows = prepared.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.next());
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void saysWhatAMatrixCantBeOf() {
        assertEquals("42S22", assertThrows(SQLException.class, () -> statement.executeQuery(
            "SELECT * FROM distance_matrix(" + docs + ", lang)")).getSQLState());
        assertThrows(SQLException.class, () -> statement.executeQuery(
            "SELECT * FROM distance_matrix(" + docs + ", keywords)"));
        assertThrows(SQLException.class, () -> statement.executeQuery(
            "SELECT lang FROM distance_matrix(" + docs + ", dense)"));
    }

    private static String rowsId(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }
}
