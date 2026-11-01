package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Deletes points and collections in real Qdrant. Every test gets its own copy of the sample
 * collections, since each one changes them. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class WriteIntegrationTest {

    private String prefix;
    private QdrantGateway gateway;
    private Connection connection;

    @BeforeEach
    void createCollections() throws SQLException {
        prefix = "jdbc_write_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, prefix);
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
    }

    @AfterEach
    void deleteCollections() throws SQLException {
        connection.close();
        SampleCollections.deleteAliases(gateway, prefix);
        SampleCollections.delete(gateway, prefix);
        gateway.close();
    }

    @Test
    void deletesThePointsTheWhereMatchesAndCountsThem() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(3, statement.executeUpdate(
                "DELETE FROM " + prefix + "products WHERE price > 50"));
            assertEquals(3, statement.getUpdateCount());
            assertNull(statement.getResultSet());
        }

        assertEquals(List.of("1", "5"), ids("SELECT _id FROM " + prefix + "products"));
    }

    @Test
    void deletesByIdUnderATableAliasAndThroughAQdrantAlias() throws SQLException {
        String view = prefix + "current";
        SampleCollections.alias(gateway, view, prefix + "products");

        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                "DELETE FROM collections." + prefix + "products t WHERE t._id = 2"));
            assertEquals(1, statement.executeUpdate("DELETE FROM " + view + " WHERE _id IN (4, 99)"));
            assertEquals(0, statement.executeUpdate("DELETE FROM " + view + " WHERE _id = 2"),
                "already deleted");
        }

        assertEquals(List.of("1", "3", "5"), ids("SELECT _id FROM " + prefix + "products"));
    }

    @Test
    void reportsTheCountThroughExecute() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertFalse(statement.execute("DELETE FROM " + prefix + "products WHERE in_stock = false"));
            assertEquals(1, statement.getLargeUpdateCount());
            assertFalse(statement.getMoreResults());
            assertEquals(-1, statement.getUpdateCount());
        }
    }

    @Test
    void truncatesAndKeepsTheCollection() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(5, statement.executeUpdate("TRUNCATE TABLE " + prefix + "products"));
        }

        assertEquals(List.of(), ids("SELECT _id FROM " + prefix + "products"));
        assertEquals(0, gateway.count(prefix + "products", null));
    }

    @Test
    void dropsACollection() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(0, statement.executeUpdate("DROP TABLE " + prefix + "events"));

            SQLException missing = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT * FROM " + prefix + "events"));
            assertEquals("42S02", missing.getSQLState(), "its columns aren't kept after the drop");

            assertEquals("42S02", assertThrows(SQLException.class,
                () -> statement.executeUpdate("DROP TABLE " + prefix + "events")).getSQLState());
            assertEquals(0, statement.executeUpdate("DROP TABLE IF EXISTS " + prefix + "events"));
        }

        assertFalse(gateway.listCollections().contains(prefix + "events"));
    }

    @Test
    void refusesWritesOnAReadOnlyConnection() throws SQLException {
        connection.setReadOnly(true);

        try (Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class,
                () -> statement.executeUpdate("DELETE FROM " + prefix + "products WHERE _id = 1"));
            assertEquals("25006", error.getSQLState());

            assertEquals(5, ids("SELECT _id FROM " + prefix + "products").size(),
                "reads still work, and nothing was deleted");
        }
    }

    @Test
    void keepsReadsAndWritesToTheirOwnMethods() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class,
                () -> statement.executeQuery("DELETE FROM " + prefix + "products WHERE _id = 1"));
            assertThrows(SQLException.class,
                () -> statement.executeUpdate("SELECT * FROM " + prefix + "products"));
        }

        assertTrue(ids("SELECT _id FROM " + prefix + "products").contains("1"),
            "executeQuery refused the DELETE before running it");
    }

    private List<String> ids(String sql) throws SQLException {
        var ids = new ArrayList<String>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getString(1));
        }

        return ids;
    }
}
