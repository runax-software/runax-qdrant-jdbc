package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
 * Adds points with INSERT in real Qdrant and reads them back, through Qdrant and through SQL.
 * Every test gets its own copy of the sample collections. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class InsertIntegrationTest {

    private String prefix;
    private String products;
    private String articles;
    private QdrantGateway gateway;
    private Connection connection;

    @BeforeEach
    void createCollections() throws SQLException {
        prefix = "jdbc_insert_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        products = prefix + "products";
        articles = prefix + "articles";
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, prefix);
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
    }

    @AfterEach
    void deleteCollections() throws SQLException {
        connection.close();
        for (String name : gateway.listCollections()) {
            if (name.startsWith(prefix)) gateway.deleteCollection(name);
        }
        gateway.close();
    }

    @Test
    void insertsPointsThatSqlReadsBack() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(2, statement.executeUpdate("INSERT INTO " + products
                + " (_id, name, price, \"specs.color\", _vector) VALUES "
                + "(10, 'Lamp shade', 12.5, 'white', [0.9, 0.1, 0.1, 0.2]), "
                + "(11, 'Desk mat', '20', NULL, [0.1, 0.1, 0.9, 0.1])"));

            assertEquals(List.of("10", "11"), column(statement, "SELECT _id FROM " + products
                + " WHERE _id IN (10, 11)"));
            assertEquals(List.of("Lamp shade"), column(statement, "SELECT name FROM " + products
                + " WHERE specs.color = 'white' AND price < 15"));
        }

        JsonNode mat = point(products, 11);
        assertEquals(20.0, mat.path("payload").path("price").asDouble());
        assertTrue(mat.path("payload").path("specs").isMissingNode(), "a NULL field is left out");
        assertEquals(4, mat.path("vector").size());
    }

    @Test
    void makesAnIdForARowWithoutOneAndReturnsIt() throws SQLException {
        String id;

        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("INSERT INTO " + products + " (name, _vector) "
                + "VALUES ('No id', [0.5, 0.5, 0.5, 0.5])", Statement.RETURN_GENERATED_KEYS));

            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                id = keys.getString("_id");
                assertFalse(keys.next());
            }

            assertEquals(List.of("No id"), column(statement, "SELECT name FROM " + products
                + " WHERE _id = '" + id + "'"));
        }

        UUID.fromString(id);
    }

    @Test
    void refusesAnIdThatIsTaken() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO " + products + " (_id, name, _vector) VALUES "
                    + "(12, 'New', [0, 0, 0, 1]), (1, 'Taken', [0, 0, 0, 1])"));

            assertEquals("23505", error.getSQLState());
            assertTrue(error.getMessage().contains("Point 1 is already in"), error.getMessage());
        }

        assertEquals("Desk lamp", point(products, 1).path("payload").path("name").asText(),
            "the taken point is as it was");
        assertTrue(points(products, 12).isEmpty(), "and no row of the statement went in");
    }

    @Test
    void insertsABatchOfPreparedRows() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO " + products + " (_id, name, in_stock, _vector) VALUES (?, ?, ?, ?)")) {
            for (int id = 20; id < 23; id++) {
                statement.setInt(1, id);
                statement.setString(2, "Item " + id);
                statement.setBoolean(3, id % 2 == 0);
                statement.setObject(4, new float[] {0.1f, 0.2f, 0.3f, id / 100f});
                statement.addBatch();
            }

            assertArrayEquals(new int[] {1, 1, 1}, statement.executeBatch());
        }

        assertEquals(true, point(products, 20).path("payload").path("in_stock").asBoolean());
        assertEquals("Item 22", point(products, 22).path("payload").path("name").asText());
    }

    /**
     * The articles have named dense, multi-, and sparse vectors; Qdrant embeds the sparse one
     * from text as it stores the point, and a search with the same text finds it.
     */
    @Test
    void insertsNamedVectorsAndOnesQdrantEmbeds() throws SQLException {
        String id = "7a1f3c2e-9b8d-4e6f-a5c4-3b2a1f0e9d8c";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO " + articles + " (_id, title, lang, year, dense, "
                + "colbert, keywords) VALUES ('" + id + "', 'Warranty claims', 'en', 2026, "
                + "[0.1, 0.1, 0.1, 0.1], '[[0.5, 0.5], [0.1, 0.9]]', "
                + "embed('warranty claims explained', 'qdrant:qdrant/bm25'))");

            assertEquals(List.of(id), column(statement, "SELECT _id FROM " + articles
                + " ORDER BY similarity(keywords, embed('warranty', 'qdrant:qdrant/bm25')) DESC LIMIT 1"));
        }

        JsonNode vectors = point(articles, id).path("vector");
        assertEquals(4, vectors.path("dense").size());
        assertEquals(2, vectors.path("colbert").size());
        assertTrue(vectors.path("keywords").path("indices").size() > 0, "BM25 made the sparse vector");
    }

    @Test
    void createsATableInsertsIntoItAndSearchesIt() throws SQLException {
        String docs = prefix + "docs";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE " + docs + " (_vector VECTOR(2), lang KEYWORD INDEX)");
            statement.executeUpdate("INSERT INTO " + docs + " (_id, lang, _vector) VALUES "
                + "(1, 'en', [1, 0]), (2, 'de', [0, 1]), (3, 'en', [0.9, 0.1])");

            assertEquals(List.of("3", "1"), column(statement, "SELECT _id FROM " + docs
                + " WHERE lang = 'en' ORDER BY similarity(_vector, [0, 1]) DESC LIMIT 5"));
        }
    }

    @Test
    void refusesToInsertOnAReadOnlyConnection() throws SQLException {
        connection.setReadOnly(true);

        try (Statement statement = connection.createStatement()) {
            assertEquals("25006", assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO " + products + " (_id, _vector) VALUES (30, [0, 0, 0, 1])")).getSQLState());
        }

        assertTrue(points(products, 30).isEmpty());
    }

    private static List<String> column(Statement statement, String sql) throws SQLException {
        var values = new ArrayList<String>();

        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }

        return values;
    }

    private JsonNode point(String collection, Object id) throws SQLException {
        return points(collection, id).get(0);
    }

    private List<JsonNode> points(String collection, Object id) throws SQLException {
        var found = new ArrayList<JsonNode>();
        gateway.request(HttpMethod.POST, "/collections/" + collection + "/points",
            Json.object("ids", Json.array(id), "with_payload", true, "with_vector", true))
            .forEach(found::add);

        return found;
    }
}
