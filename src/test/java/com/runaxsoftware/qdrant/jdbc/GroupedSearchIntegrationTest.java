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
 * Searches a real collection of document chunks for the best chunks of each document, with
 * DISTINCT ON and LIMIT ... BY. Needs Qdrant: {@code docker compose up -d}.
 *
 * <p>The chunks, by how close they are to {@code [1, 0]}: 1, 2 and 3 of document a, 4 and 5 of
 * b, 6 with no document at all, and 7 of c.
 */
@Tag("integration")
class GroupedSearchIntegrationTest {

    private static final String NEAREST = " ORDER BY similarity(dense, [1, 0]) DESC";

    private String chunks;
    private QdrantGateway gateway;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void createChunks() throws SQLException {
        chunks = "jdbc_groups_" + UUID.randomUUID().toString().substring(0, 8);
        gateway = TestQdrant.gateway();
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
        statement = connection.createStatement();

        statement.executeUpdate("CREATE TABLE " + chunks
            + " (dense VECTOR(2), l2 VECTOR(2) DISTANCE EUCLID)");
        statement.executeUpdate("INSERT INTO " + chunks + " (_id, doc, page, dense, l2) VALUES "
            + "(1, 'a', 1, [1, 0], [1, 0]), (2, 'a', 2, [0.9, 0.1], [0.9, 0.1]), "
            + "(3, 'a', 3, [0.8, 0.2], [0.8, 0.2]), (4, 'b', 1, [0.7, 0.3], [0.7, 0.3]), "
            + "(5, 'b', 2, [0.6, 0.4], [0.6, 0.4]), (7, 'c', 1, [0.4, 0.6], [0.4, 0.6])");
        statement.executeUpdate("INSERT INTO " + chunks + " (_id, page, dense, l2) VALUES "
            + "(6, 9, [0.5, 0.5], [0.5, 0.5])");
    }

    @AfterEach
    void deleteChunks() throws SQLException {
        statement.close();
        connection.close();
        gateway.deleteCollection(chunks);
        gateway.close();
    }

    @Test
    void keepsTheBestChunkOfEachDocumentWithDistinctOn() throws SQLException {
        assertEquals(List.of("1 a", "4 b", "7 c"), rows("SELECT DISTINCT ON (doc) _id, doc FROM "
            + chunks + NEAREST + " LIMIT 10"));
        assertEquals(List.of("1 a", "4 b"), rows("SELECT DISTINCT ON (doc) _id, doc FROM "
            + chunks + NEAREST + " LIMIT 2"));
    }

    @Test
    void keepsSeveralChunksOfEachDocumentWithLimitBy() throws SQLException {
        assertEquals(List.of("1 a", "2 a", "4 b", "5 b", "7 c"), rows("SELECT _id, doc FROM "
            + chunks + NEAREST + " LIMIT 2 BY doc LIMIT 10"));
    }

    @Test
    void countsRowsWithTheStatementsLimitAndOffsetAsSqlDoes() throws SQLException {
        assertEquals(List.of("1 a", "2 a", "4 b"), rows("SELECT _id, doc FROM " + chunks + NEAREST
            + " LIMIT 2 BY doc LIMIT 3"), "three rows, not three documents");
        assertEquals(List.of("4 b", "5 b"), rows("SELECT _id, doc FROM " + chunks + NEAREST
            + " LIMIT 2 BY doc LIMIT 2 OFFSET 2"));
    }

    @Test
    void filtersBeforeItGroupsAndReturnsTheScore() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT _id, _score FROM " + chunks
            + " WHERE page > 1" + NEAREST + " LIMIT 1 BY doc LIMIT 10")) {
            assertTrue(rows.next());
            assertEquals("2", rows.getString("_id"));
            double first = rows.getDouble("_score");
            assertTrue(rows.next());
            assertEquals("5", rows.getString("_id"));
            assertTrue(rows.getDouble("_score") < first, "best first");
            assertTrue(!rows.next(), "document c has no page past the first");
        }
    }

    @Test
    void groupsByAFieldOfWholeNumbers() throws SQLException {
        assertEquals(List.of("1", "2", "3", "6"), rows("SELECT DISTINCT ON (page) _id FROM "
            + chunks + NEAREST + " LIMIT 10"));
    }

    @Test
    void ordersByADistanceWhoseLowestScoreIsBest() throws SQLException {
        assertEquals(List.of("1 a", "4 b", "7 c"), rows("SELECT DISTINCT ON (doc) _id, doc FROM "
            + chunks + " ORDER BY similarity(l2, [1, 0]) DESC LIMIT 10"));
    }

    @Test
    void takesTheSearchVectorAsAParameter() throws SQLException {
        try (PreparedStatement prepared = connection.prepareStatement("SELECT _id FROM " + chunks
            + " WHERE page >= ? ORDER BY similarity(dense, ?) DESC LIMIT 1 BY doc LIMIT 5")) {
            prepared.setInt(1, 1);
            prepared.setObject(2, new double[] {0.0, 1.0});

            try (ResultSet rows = prepared.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("7", rows.getString("_id"), "document c is closest to [0, 1]");
            }
        }
    }

    @Test
    void saysWhatAGroupedSearchNeeds() {
        assertThrows(SQLException.class, () -> statement.executeQuery(
            "SELECT DISTINCT ON (doc) _id FROM " + chunks + " LIMIT 5"));
        assertThrows(SQLException.class, () -> statement.executeQuery(
            "SELECT _id FROM " + chunks + NEAREST + " LIMIT 1 BY dense LIMIT 5"));
    }

    private List<String> rows(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();

        try (ResultSet found = statement.executeQuery(sql)) {
            int columns = found.getMetaData().getColumnCount();

            while (found.next()) {
                var row = new StringBuilder(found.getString(1));
                for (int i = 2; i <= columns; i++) row.append(' ').append(found.getString(i));
                rows.add(row.toString());
            }
        }

        return rows;
    }
}
