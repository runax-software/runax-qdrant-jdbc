package com.runaxsoftware.qdrant.jdbc;

import static com.runaxsoftware.qdrant.jdbc.support.Json.array;
import static com.runaxsoftware.qdrant.jdbc.support.Json.object;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
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
 * Searches with text that Qdrant embeds itself. Every Qdrant runs BM25, so the collection's
 * sparse vector is built from each point's text by Qdrant too, and the search needs no model of
 * the driver's or the test's. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class DocumentSearchIntegrationTest {

    private static final String BM25 = "qdrant/bm25";

    private static final String NAME =
        "jdbc_document_" + UUID.randomUUID().toString().substring(0, 8);

    private static QdrantGateway gateway;
    private static Connection connection;

    @BeforeAll
    static void createCollection() throws SQLException {
        gateway = TestQdrant.gateway();
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE " + NAME + " (dense VECTOR(2), "
                + "keywords SPARSE_VECTOR MODIFIER IDF, lang KEYWORD INDEX)");
        }

        gateway.request(HttpMethod.PUT, "/collections/" + NAME + "/points?wait=true",
            object("points", array(
                point(1, "How to ask for a refund of your order", "en"),
                point(2, "Shipping times for every region", "en"),
                point(3, "Our refund policy, explained step by step", "en"),
                point(4, "Refund policy in German: Rückgaberecht", "de"))));
    }

    @AfterAll
    static void deleteCollection() throws SQLException {
        if (connection != null) connection.close();

        if (gateway != null) {
            gateway.deleteCollection(NAME);
            gateway.close();
        }
    }

    @Test
    void ranksThePointsWhoseTextMatchesFirst() throws SQLException {
        List<String> ids = ids("SELECT _id, _score FROM " + NAME
            + " ORDER BY similarity(keywords, document('refund policy', '" + BM25 + "')) DESC "
            + "LIMIT 10");

        assertTrue(ids.size() >= 2, ids.toString());
        assertTrue(List.of("3", "4").contains(ids.get(0)),
            "a point with both words comes first: " + ids);
        assertTrue(!ids.contains("2"), "shipping shares no word with the query: " + ids);
    }

    @Test
    void filtersWhatQdrantRanks() throws SQLException {
        assertEquals(List.of("4"), ids("SELECT _id FROM " + NAME + " WHERE lang = 'de' "
            + "ORDER BY similarity(keywords, document('refund policy', '" + BM25 + "')) DESC "
            + "LIMIT 10"));
    }

    @Test
    void explainsThatAHostedModelNeedsAnInferenceService() {
        SQLException error = assertThrows(SQLException.class, () -> ids("SELECT _id FROM " + NAME
            + " ORDER BY similarity(dense, document('refund policy', "
            + "'sentence-transformers/all-minilm-l6-v2')) DESC LIMIT 3"));

        assertTrue(error.getMessage().contains("runs no inference service"), error.getMessage());
    }

    private static List<String> ids(String sql) throws SQLException {
        var ids = new ArrayList<String>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getString("_id"));
        }

        return ids;
    }

    // Qdrant embeds the point's text into its BM25 vector as it stores it
    private static Object point(int id, String text, String lang) {
        return object("id", id,
            "vector", object(
                "dense", array(0.1, 0.2),
                "keywords", object("text", text, "model", BM25)),
            "payload", object("text", text, "lang", lang));
    }
}
