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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Changes vectors with UPDATE in real Qdrant and reads back what Qdrant stored. The two halves
 * reach Qdrant differently and both are worth running against a server: a write goes through
 * {@code update_vectors}, which takes point IDs the driver has to read first, and a delete
 * through {@code delete_vectors}, which takes the filter. Every test gets its own copy of the
 * sample collections. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class VectorUpdateIntegrationTest {

    private String prefix;
    private String products;
    private String articles;
    private QdrantGateway gateway;
    private Connection connection;

    @BeforeEach
    void createCollections() throws SQLException {
        prefix = "jdbc_vecupd_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        products = prefix + "products";
        articles = prefix + "articles";
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
     * The unnamed vector of the products collection, written as a tool sends a cell: JSON text.
     */
    @Test
    void writesTheUnnamedVectorOfOnePoint() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("UPDATE " + products
                + " SET _vector = '[0.0, 0.0, 0.0, 1.0]' WHERE _id = 1"));
        }

        assertVector(vector(products, 1, null), unit(0.0, 0.0, 0.0, 1.0));
        assertEquals("Desk lamp", payload(products, 1).path("name").asText(),
            "the payload is untouched");
    }

    @Test
    void writesAVectorWrittenOutInTheStatement() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + products
                + " SET _vector = [1.0, 0.0, 0.0, 0.0] WHERE _id = 2");
        }

        assertVector(vector(products, 2, null), unit(1.0, 0.0, 0.0, 0.0));
    }

    /**
     * Qdrant's {@code update_vectors} takes point IDs, so the driver reads the IDs the WHERE
     * matches first. More than one point means more than one ID, all written in one call.
     */
    @Test
    void writesTheSameVectorToEveryPointTheWhereMatches() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(3, statement.executeUpdate("UPDATE " + products
                + " SET _vector = '[0.5, 0.5, 0.5, 0.5]' WHERE price > 50"));
        }

        for (long id : new long[] {2, 3, 4}) {
            assertVector(vector(products, id, null), unit(0.5, 0.5, 0.5, 0.5));
        }
        assertVector(vector(products, 1, null), unit(0.9, 0.1, 0.1, 0.2));   // under 50, kept
    }

    @Test
    void writesOneNamedVectorAndLeavesTheOthers() throws SQLException {
        String id = "5c56c793-69f3-4fbf-87e6-c4bf54c28c26";

        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("UPDATE " + articles
                + " SET dense = '[0.9, 0.9, 0.9, 0.9]' WHERE _id = '" + id + "'"));
        }

        // The articles' dense vector is compared by dot product, so Qdrant stores it as written
        assertVector(vector(articles, id, "dense"), 0.9, 0.9, 0.9, 0.9);
        assertFalse(vector(articles, id, "colbert").isMissingNode(), "the multivector is kept");
        assertFalse(vector(articles, id, "keywords").isMissingNode(), "the sparse vector is kept");
    }

    @Test
    void writesAMultivectorAndASparseVectorGivenAsJsonText() throws SQLException {
        String id = "0b3e9a2c-7a51-4c6e-9d0f-2f4a6c8e1b37";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + articles
                + " SET colbert = '[[0.1, 0.2], [0.3, 0.4], [0.5, 0.6]]' WHERE _id = '" + id + "'");
            statement.executeUpdate("UPDATE " + articles
                + " SET keywords = '{\"indices\": [3, 5], \"values\": [0.25, 0.75]}' "
                + "WHERE _id = '" + id + "'");
        }

        assertEquals(3, vector(articles, id, "colbert").size(), "three vectors of two");
        assertEquals(Json.array(3, 5), vector(articles, id, "keywords").path("indices"));
    }

    /**
     * A vector set to NULL is deleted, which Qdrant does by filter — no IDs to read. Qdrant
     * allows a point to lose even its only vector, and the point stays with its payload.
     */
    @Test
    void deletesAVectorSetToNull() throws SQLException {
        String id = "5c56c793-69f3-4fbf-87e6-c4bf54c28c26";

        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("UPDATE " + articles
                + " SET dense = NULL WHERE _id = '" + id + "'"));
        }

        assertTrue(vector(articles, id, "dense").isMissingNode());
        assertFalse(vector(articles, id, "colbert").isMissingNode(), "the others are kept");
        assertEquals("Refund policy explained", payload(articles, id).path("title").asText());
    }

    @Test
    void deletesTheUnnamedVectorAndLeavesThePoint() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("UPDATE " + products
                + " SET _vector = NULL WHERE _id = 5"));
        }

        assertTrue(vector(products, 5, null).isMissingNode());
        assertEquals("Cable tray", payload(products, 5).path("name").asText(),
            "the point is still there");
    }

    /**
     * Once a vector can be deleted, asking which points still have one is a fair question, so
     * IS NULL and IS NOT NULL work on the unnamed vector too.
     */
    @Test
    void findsThePointsLeftWithoutAVector() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + products + " SET _vector = NULL WHERE _id IN (1, 2)");
        }

        assertEquals(List.of("1", "2"),
            sorted(ids("SELECT _id FROM " + products + " WHERE _vector IS NULL")));
        assertEquals(List.of("3", "4", "5"),
            sorted(ids("SELECT _id FROM " + products + " WHERE _vector IS NOT NULL")));
    }

    @Test
    void setsAFieldAndAVectorInOneStatement() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("UPDATE " + products
                + " SET name = 'Desk lamp XL', _vector = '[0.1, 0.1, 0.1, 0.1]' WHERE _id = 1"));
        }

        assertEquals("Desk lamp XL", payload(products, 1).path("name").asText());
        assertVector(vector(products, 1, null), unit(0.1, 0.1, 0.1, 0.1));
    }

    @Test
    void writesAVectorGivenAsAParameter() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE " + products + " SET _vector = ? WHERE _id = ?")) {
            statement.setString(1, "[0.2, 0.2, 0.2, 0.2]");
            statement.setString(2, "4");

            assertEquals(1, statement.executeUpdate());
        }

        assertVector(vector(products, 4, null), unit(0.2, 0.2, 0.2, 0.2));
    }

    /**
     * A vector written now is searchable now: the point the new vector is nearest to comes back
     * first, which is the end the whole feature is for.
     */
    @Test
    void makesTheNewVectorSearchable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + products
                + " SET _vector = '[0.0, 0.0, 0.0, 1.0]' WHERE _id = 1");
        }

        assertEquals("1", ids("SELECT _id FROM " + products
            + " ORDER BY similarity(_vector, [0.0, 0.0, 0.0, 1.0]) DESC LIMIT 1").get(0));
    }

    @Test
    void refusesVectorsQdrantWouldNotTake() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // The unnamed vector of this collection has four dimensions
            assertEquals("22018", assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE " + products + " SET _vector = '[0.1, 0.2]' WHERE _id = 1")).getSQLState());
            assertEquals("22018", assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE " + products + " SET _vector = 'nonsense' WHERE _id = 1")).getSQLState());

            // A sparse vector isn't a list of numbers
            assertEquals("22018", assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE " + articles + " SET keywords = '[0.1, 0.2]' WHERE year = 2024"))
                .getSQLState());

            connection.setReadOnly(true);
            assertEquals("25006", assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE " + products + " SET _vector = '[0.1, 0.2, 0.3, 0.4]' WHERE _id = 1"))
                .getSQLState());
        }

        assertVector(vector(products, 1, null), unit(0.9, 0.1, 0.1, 0.2));   // nothing changed
    }

    @Test
    void changesNothingWhenNoPointMatches() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(0, statement.executeUpdate("UPDATE " + products
                + " SET _vector = '[0.1, 0.2, 0.3, 0.4]' WHERE price > 10000"));
        }

        assertVector(vector(products, 1, null), unit(0.9, 0.1, 0.1, 0.2));
    }

    /**
     * Asserts a vector component by component, within float tolerance: Qdrant stores vectors as
     * 32-bit floats, so a value read back is close to what was written rather than equal to it.
     */
    private static void assertVector(JsonNode actual, double... expected) {
        assertTrue(actual.isArray(), "not a vector: " + actual);
        assertEquals(expected.length, actual.size(), "dimensions of " + actual);

        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual.path(i).asDouble(), 1e-6, "component " + i);
        }
    }

    /**
     * What Qdrant stores for a Cosine collection: the vector scaled to unit length. The sample
     * products are compared by cosine distance, so a vector written there is read back
     * normalized, and an expectation has to be too.
     */
    private static double[] unit(double... values) {
        double length = 0;
        for (double value : values) length += value * value;
        length = Math.sqrt(length);

        double[] scaled = new double[values.length];
        for (int i = 0; i < values.length; i++) scaled[i] = values[i] / length;

        return scaled;
    }

    /** One point's vector, by Qdrant's name for it, or the unnamed one when {@code name} is null. */
    private JsonNode vector(String collection, Object id, String name) throws SQLException {
        JsonNode vectors = gateway.request(HttpMethod.POST, "/collections/" + collection + "/points",
            Json.object("ids", Json.array(id), "with_vector", true)).path(0).path("vector");

        return name == null && vectors.isArray() ? vectors : vectors.path(name == null ? "" : name);
    }

    private JsonNode payload(String collection, Object id) throws SQLException {
        return gateway.request(HttpMethod.POST, "/collections/" + collection + "/points",
            Json.object("ids", Json.array(id), "with_payload", true)).path(0).path("payload");
    }

    private List<String> ids(String sql) throws SQLException {
        var ids = new ArrayList<String>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getString(1));
        }

        return ids;
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }
}
