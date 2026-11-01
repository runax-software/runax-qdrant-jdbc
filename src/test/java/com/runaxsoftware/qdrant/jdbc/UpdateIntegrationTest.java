package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Changes points with UPDATE in real Qdrant and reads back what Qdrant stored. Every test gets
 * its own copy of the sample collections. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class UpdateIntegrationTest {

    private String prefix;
    private String products;
    private QdrantGateway gateway;
    private Connection connection;

    @BeforeEach
    void createCollections() throws SQLException {
        prefix = "jdbc_update_" + UUID.randomUUID().toString().substring(0, 8) + "_";
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
     * What DataGrip's data editor sends when a cell is saved: the ID as text, every value as a
     * parameter.
     */
    @Test
    void savesACellAsTheDataEditorDoes() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE collections." + products + " SET name = ?, price = ? WHERE _id = ?")) {
            statement.setString(1, "Desk lamp XL");
            statement.setString(2, "49.5");
            statement.setString(3, "1");

            assertEquals(1, statement.executeUpdate());
        }

        JsonNode payload = payload(1);
        assertEquals("Desk lamp XL", payload.path("name").asText());
        assertEquals(49.5, payload.path("price").asDouble());
        assertTrue(payload.path("price").isNumber(), "a number, as the column is");
        assertEquals("black", payload.path("specs").path("color").asText(), "the rest is kept");
    }

    @Test
    void changesEveryPointTheWhereMatches() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(4, statement.executeUpdate("UPDATE " + products
                + " SET in_stock = false WHERE in_stock = true"));
            assertEquals(0, statement.executeUpdate("UPDATE " + products
                + " SET in_stock = true WHERE _id = 99"), "nothing matches");
        }

        for (int id = 1; id <= 5; id++) {
            assertFalse(payload(id).path("in_stock").asBoolean(), "point " + id);
        }
    }

    @Test
    void setsAndDeletesFieldsInsideAnObject() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + products + " t SET specs.color = 'red', "
                + "\"specs.weight\" = NULL, tags = '[\"sale\"]', rating = 5 WHERE t._id = 2");
        }

        JsonNode payload = payload(2);
        assertEquals("red", payload.path("specs").path("color").asText());
        assertTrue(payload.path("specs").path("weight").isMissingNode());
        assertEquals(Json.array("sale"), payload.path("tags"));
        assertEquals(5, payload.path("rating").asInt(), "a new field");
    }

    @Test
    void replacesTheWholePayload() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + products + " SET _payload = '{\"name\": \"Box\"}', "
                + "price = 1 WHERE _id = 3");
        }

        assertEquals(Json.object("name", "Box", "price", 1.0), payload(3),
            "price is a DOUBLE column, so 1 is stored as one");
    }

    @Test
    void refusesAnUpdateWithoutWhereAndOnAReadOnlyConnection() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class,
                () -> statement.executeUpdate("UPDATE " + products + " SET price = 0"));

            connection.setReadOnly(true);
            assertEquals("25006", assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE " + products + " SET price = 0 WHERE _id = 1")).getSQLState());
        }

        assertEquals(39.9, payload(1).path("price").asDouble(), "nothing changed");
    }

    private JsonNode payload(long id) throws SQLException {
        return gateway.request(HttpMethod.POST, "/collections/" + products + "/points",
            Json.object("ids", Json.array(id), "with_payload", true)).path(0).path("payload");
    }
}
