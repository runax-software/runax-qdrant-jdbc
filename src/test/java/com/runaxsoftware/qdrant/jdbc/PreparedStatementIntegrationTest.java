package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs prepared statements against real Qdrant, so a value given through a setter filters,
 * searches, and deletes as the same value written in the SQL would. Needs Qdrant:
 * {@code docker compose up -d}.
 */
@Tag("integration")
class PreparedStatementIntegrationTest {

    private String prefix;
    private String products;
    private QdrantGateway gateway;
    private Connection connection;

    @BeforeEach
    void createCollections() throws SQLException {
        prefix = "jdbc_prepared_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        products = prefix + "products";
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, prefix);
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
    }

    @AfterEach
    void deleteCollections() throws SQLException {
        connection.close();
        SampleCollections.delete(gateway, prefix);
        gateway.close();
    }

    @Test
    void filtersWithTheGivenValues() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM " + products + " WHERE price > ? AND specs.color = ?")) {
            statement.setDouble(1, 50);
            statement.setString(2, "black");
            assertEquals(List.of("4"), ids(statement));

            statement.setString(2, "white");
            assertEquals(List.of("3"), ids(statement));
        }
    }

    @Test
    void matchesAListIdsAndDateTimes() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM " + products + " WHERE name IN (?, ?) OR _id = ?")) {
            statement.setString(1, "Desk lamp");
            statement.setString(2, "Cable tray");
            statement.setLong(3, 3);
            assertEquals(List.of("1", "3", "5"), ids(statement));
        }

        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM " + products + " WHERE created_at >= ?")) {
            statement.setTimestamp(1, Timestamp.from(Instant.parse("2024-06-01T00:00:00Z")));
            assertEquals(List.of("3", "4", "5"), ids(statement));
        }
    }

    @Test
    void searchesWithAVectorGivenAsAnArray() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT _id FROM " + products
            + " WHERE in_stock = ? ORDER BY similarity(_vector, ?) DESC LIMIT 1")) {
            statement.setBoolean(1, true);
            statement.setObject(2, new double[] {0.9, 0.1, 0.1, 0.2});

            assertEquals(List.of("1"), ids(statement));
        }
    }

    @Test
    void pagesWithALimitAndOffsetGivenAsValues() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM " + products + " LIMIT ? OFFSET ?")) {
            statement.setInt(1, 2);
            statement.setInt(2, 1);
            assertEquals(List.of("2", "3"), ids(statement));

            statement.setInt(2, 3);
            assertEquals(List.of("4", "5"), ids(statement), "the next page, from the same statement");
        }

        // A search's offset goes to Qdrant: the same rows as the numbers written out
        String search = "SELECT _id FROM " + products
            + " ORDER BY similarity(_vector, [0.9, 0.1, 0.1, 0.2]) DESC";
        try (PreparedStatement statement = connection.prepareStatement(search + " LIMIT ? OFFSET ?")) {
            statement.setInt(1, 2);
            statement.setInt(2, 1);
            assertEquals(written(search + " LIMIT 2 OFFSET 1"), ids(statement));
        }

        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM " + products + " LIMIT ?")) {
            statement.setInt(1, -1);
            assertThrows(SQLDataException.class, statement::executeQuery);
        }
    }

    @Test
    void deletesInABatch() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + products + " WHERE _id = ?")) {
            for (long id : new long[] {1, 2, 99}) {
                statement.setLong(1, id);
                statement.addBatch();
            }

            assertArrayEquals(new int[] {1, 1, 0}, statement.executeBatch());
            assertArrayEquals(new int[0], statement.executeBatch(), "the batch is cleared after it runs");
        }

        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM " + products + " WHERE _id IN (?, ?, ?)")) {
            statement.setInt(1, 1);
            statement.setInt(2, 2);
            statement.setInt(3, 3);
            assertEquals(List.of("3"), ids(statement));
        }
    }

    @Test
    void reportsWhatABatchDidBeforeItFailed() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + products + " WHERE _id = ?")) {
            statement.setLong(1, 1);
            statement.addBatch();
            statement.setString(1, "not-an-id");
            statement.addBatch();

            BatchUpdateException error = assertThrows(BatchUpdateException.class,
                statement::executeBatch);
            assertArrayEquals(new int[] {1}, error.getUpdateCounts());
        }
    }

    private List<String> written(String sql) throws SQLException {
        var ids = new ArrayList<String>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getString(1));
        }

        return ids;
    }

    private static List<String> ids(PreparedStatement statement) throws SQLException {
        var ids = new ArrayList<String>();

        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) ids.add(rows.getString(1));
        }

        return ids;
    }
}
