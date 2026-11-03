package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.Json;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
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
import java.util.UUID;

/**
 * Runs INSERT ... ON CONFLICT against real Qdrant. The three answers to a taken ID map to two
 * upsert modes and an ID lookup, and what Qdrant does with each is worth checking against a
 * server: that insert_only skips a point without reporting it, that upsert replaces a whole
 * point rather than merging into it, and that update_filter guards only the points already
 * there. Every test gets its own copy of the sample collections. Needs Qdrant:
 * {@code docker compose up -d}.
 */
@Tag("integration")
class OnConflictIntegrationTest {

    private String prefix;
    private String products;
    private QdrantGateway gateway;
    private Connection connection;

    @BeforeEach
    void createCollections() throws SQLException {
        prefix = "jdbc_conflict_" + UUID.randomUUID().toString().substring(0, 8) + "_";
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

    /**
     * Without a clause, a taken ID is an error before anything is written, as a primary key's
     * is — and the message now says which clause to add.
     */
    @Test
    void failsOnATakenIdAndSaysWhatToWriteInstead() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO " + products + " (_id, name, _vector) VALUES "
                    + "(1, 'Replaced', '[0.1, 0.1, 0.1, 0.1]')"));

            assertEquals("23505", error.getSQLState());
            assertTrue(error.getMessage().contains("ON CONFLICT"), error.getMessage());
        }

        assertEquals("Desk lamp", payload(1).path("name").asText(), "nothing was written");
    }

    /**
     * DO NOTHING leaves the point as it is. Qdrant's insert_only mode skips it and reports
     * nothing, so the row count counts the rows the statement gave, not the points written.
     */
    @Test
    void leavesAPointThatIsThereOnDoNothing() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(2, statement.executeUpdate("INSERT INTO " + products
                + " (_id, name, _vector) VALUES "
                + "(1, 'Skipped', '[0.1, 0.1, 0.1, 0.1]'), "
                + "(99, 'Brand new', '[0.2, 0.2, 0.2, 0.2]') "
                + "ON CONFLICT (_id) DO NOTHING"));
        }

        assertEquals("Desk lamp", payload(1).path("name").asText(), "kept");
        assertEquals("Brand new", payload(99).path("name").asText(), "inserted");
    }

    /**
     * DO UPDATE replaces the point with the row. Qdrant's upsert writes a whole point, so a
     * field the statement doesn't name is gone rather than kept — which is why DO UPDATE takes
     * no SET list.
     */
    @Test
    void replacesTheWholePointOnDoUpdate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("INSERT INTO " + products
                + " (_id, name, _vector) VALUES (1, 'Desk lamp XL', '[0.0, 0.0, 0.0, 1.0]') "
                + "ON CONFLICT (_id) DO UPDATE"));
        }

        JsonNode payload = payload(1);
        assertEquals("Desk lamp XL", payload.path("name").asText());
        assertTrue(payload.path("price").isMissingNode(),
            "the row is the point: a field it doesn't name is gone");
    }

    @Test
    void insertsAndReplacesInTheSameStatement() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(2, statement.executeUpdate("INSERT INTO " + products
                + " (_id, name, _vector) VALUES "
                + "(1, 'Replaced', '[0.5, 0.5, 0.5, 0.5]'), "
                + "(98, 'Added', '[0.5, 0.5, 0.5, 0.5]') "
                + "ON CONFLICT (_id) DO UPDATE"));
        }

        assertEquals("Replaced", payload(1).path("name").asText());
        assertEquals("Added", payload(98).path("name").asText());
    }

    /**
     * The WHERE of a DO UPDATE reads the stored point. Qdrant applies it only to the points
     * already there and inserts new ones whatever it says, which is what the clause means in
     * SQL — so one statement can be a conditional refresh.
     */
    @Test
    void replacesOnlyThePointsTheConditionMatches() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(3, statement.executeUpdate("INSERT INTO " + products
                + " (_id, name, _vector) VALUES "
                + "(1, 'Cheap replaced', '[0.5, 0.5, 0.5, 0.5]'), "
                + "(3, 'Dear kept', '[0.5, 0.5, 0.5, 0.5]'), "
                + "(97, 'New anyway', '[0.5, 0.5, 0.5, 0.5]') "
                + "ON CONFLICT (_id) DO UPDATE WHERE price < 100"));
        }

        assertEquals("Cheap replaced", payload(1).path("name").asText(), "39.9, so replaced");
        assertEquals("Standing desk", payload(3).path("name").asText(), "449, so kept");
        assertEquals("New anyway", payload(97).path("name").asText(),
            "a new point is inserted whatever the condition says");
    }

    /**
     * The guard is a full `WHERE`, so it takes every condition a `SELECT` does — including the
     * pattern and full-text conditions. Worth pinning: the clause and those conditions were
     * built separately, and this is the one place they meet.
     */
    @Test
    void guardsADoUpdateWithAPatternCondition() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(2, statement.executeUpdate("INSERT INTO " + products
                + " (_id, name, _vector) VALUES "
                + "(1, 'Lamp replaced', '[0.5, 0.5, 0.5, 0.5]'), "
                + "(2, 'Chair kept', '[0.5, 0.5, 0.5, 0.5]') "
                + "ON CONFLICT (_id) DO UPDATE WHERE name LIKE 'Desk%'"));
        }

        assertEquals("Lamp replaced", payload(1).path("name").asText(), "was 'Desk lamp'");
        assertEquals("Office chair", payload(2).path("name").asText(), "no 'Desk' prefix, so kept");
    }

    @Test
    void returnsTheGeneratedKeysOfAnUpsert() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + products
                + " (name, _vector) VALUES ('No id given', '[0.5, 0.5, 0.5, 0.5]') "
                + "ON CONFLICT (_id) DO UPDATE", Statement.RETURN_GENERATED_KEYS);

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                UUID.fromString(keys.getString(1));
            }
        }
    }

    @Test
    void upsertsABatchOfPreparedRows() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + products
            + " (_id, name, _vector) VALUES (?, ?, ?) ON CONFLICT (_id) DO UPDATE")) {
            statement.setString(1, "1");
            statement.setString(2, "First");
            statement.setString(3, "[0.5, 0.5, 0.5, 0.5]");
            statement.addBatch();

            statement.setString(1, "96");
            statement.setString(2, "Second");
            statement.setString(3, "[0.5, 0.5, 0.5, 0.5]");
            statement.addBatch();

            assertEquals(2, statement.executeBatch().length);
        }

        assertEquals("First", payload(1).path("name").asText());
        assertEquals("Second", payload(96).path("name").asText());
    }

    @Test
    void refusesConflictClausesItCantRun() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Qdrant has one key, the point ID
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO " + products
                + " (_id, name, _vector) VALUES (1, 'x', '[0.5, 0.5, 0.5, 0.5]') "
                + "ON CONFLICT (name) DO NOTHING"));

            // A whole point is replaced, so there's no column list to set
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO " + products
                + " (_id, name, _vector) VALUES (1, 'x', '[0.5, 0.5, 0.5, 0.5]') "
                + "ON CONFLICT (_id) DO UPDATE SET name = 'x'"));

            connection.setReadOnly(true);
            assertEquals("25006", assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO " + products
                    + " (_id, name, _vector) VALUES (1, 'x', '[0.5, 0.5, 0.5, 0.5]') "
                    + "ON CONFLICT (_id) DO UPDATE")).getSQLState());
        }

        assertEquals("Desk lamp", payload(1).path("name").asText(), "nothing changed");
    }

    private JsonNode payload(long id) throws SQLException {
        return gateway.request(HttpMethod.POST, "/collections/" + products + "/points",
            Json.object("ids", Json.array(id), "with_payload", true)).path(0).path("payload");
    }
}
