package com.runaxsoftware.qdrant.jdbc;

import static com.runaxsoftware.qdrant.jdbc.support.Json.array;
import static com.runaxsoftware.qdrant.jdbc.support.Json.object;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Searches with {@code embed()} against real Qdrant. Every Qdrant runs BM25, so the sparse
 * vector is embedded by Qdrant both when points are stored and when they're searched. OpenAI is
 * a stand-in server that answers as its embeddings API does, so the driver's own embedding runs
 * end to end without a key. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class EmbedSearchIntegrationTest {

    private static final String BM25 = "qdrant:qdrant/bm25";

    private static final String NAME = "jdbc_embed_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String KEPT = NAME + "_kept";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static QdrantGateway gateway;
    private static Connection connection;
    private static HttpServer openAi;
    private static final List<JsonNode> openAiRequests = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void createCollections() throws SQLException, IOException {
        openAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        openAi.createContext("/v1/embeddings", exchange -> {
            openAiRequests.add(JSON.readTree(exchange.getRequestBody()));

            // "refund" is near point 1's dense vector, anything else near point 2's
            boolean refund = openAiRequests.get(openAiRequests.size() - 1).path("input").asText()
                .contains("refund");
            byte[] body = ("{\"data\": [{\"embedding\": " + (refund ? "[1.0, 0.0]" : "[0.0, 1.0]")
                + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        openAi.start();

        Properties properties = TestQdrant.properties();
        properties.setProperty("openaiApiKey", "sk-test");
        properties.setProperty("openaiUrl", "http://127.0.0.1:" + openAi.getAddress().getPort() + "/v1");

        gateway = TestQdrant.gateway();
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), properties);

        for (String name : List.of(NAME, KEPT)) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE " + name + " (dense VECTOR(2), "
                    + "keywords SPARSE_VECTOR MODIFIER IDF, lang KEYWORD INDEX)");
            }

            gateway.request(HttpMethod.PUT, "/collections/" + name + "/points?wait=true",
                object("points", array(
                    point(1, "How to ask for a refund of your order", "en", 1.0, 0.0),
                    point(2, "Shipping times for every region", "en", 0.0, 1.0),
                    point(3, "Our refund policy, explained step by step", "en", 0.9, 0.1),
                    point(4, "Refund policy in German: Rückgaberecht", "de", 0.8, 0.2))));
        }

        // The models KEPT's vectors were embedded with, so embed() needs none named
        gateway.request(HttpMethod.PATCH, "/collections/" + KEPT, object("metadata", object(
            "runax.embed.keywords", BM25, "runax.embed.dense", "openai:text-embedding-3-small")));
    }

    @AfterAll
    static void deleteCollections() throws SQLException {
        if (connection != null) connection.close();
        if (openAi != null) openAi.stop(0);

        if (gateway != null) {
            gateway.deleteCollection(NAME);
            gateway.deleteCollection(KEPT);
            gateway.close();
        }
    }

    @Test
    void ranksThePointsWhoseTextMatchesWithAModelQdrantRuns() throws SQLException {
        int calls = openAiRequests.size();
        List<String> ids = ids("SELECT _id, _score FROM " + NAME
            + " ORDER BY similarity(keywords, embed('refund policy', '" + BM25 + "')) DESC LIMIT 10");

        assertTrue(List.of("3", "4").contains(ids.get(0)), "both words come first: " + ids);
        assertFalse(ids.contains("2"), "shipping shares no word with the query: " + ids);
        assertEquals(calls, openAiRequests.size(), "Qdrant embedded the text, not the driver");
    }

    @Test
    void filtersWhatQdrantRanks() throws SQLException {
        assertEquals(List.of("4"), ids("SELECT _id FROM " + NAME + " WHERE lang = 'de' "
            + "ORDER BY similarity(keywords, embed('refund policy', '" + BM25 + "')) DESC LIMIT 10"));
    }

    @Test
    void embedsWithAProviderTheDriverCalls() throws SQLException {
        List<String> ids = ids("SELECT _id FROM " + NAME + " ORDER BY similarity(dense, "
            + "embed('refund please', 'openai:text-embedding-3-small')) DESC LIMIT 2");

        assertEquals(List.of("1", "3"), ids);
        JsonNode request = openAiRequests.get(openAiRequests.size() - 1);
        assertEquals("text-embedding-3-small", request.path("model").asText());
        assertEquals("refund please", request.path("input").asText());
    }

    @Test
    void embedsWithTheModelsTheCollectionKeeps() throws SQLException {
        assertTrue(List.of("3", "4").contains(ids("SELECT _id FROM " + KEPT
            + " ORDER BY similarity(keywords, embed('refund policy')) DESC LIMIT 1").get(0)));
        assertEquals(List.of("2"), ids("SELECT _id FROM " + KEPT
            + " ORDER BY similarity(dense, embed('shipping')) DESC LIMIT 1"));

        SQLException wrong = assertThrows(SQLException.class, () -> ids("SELECT _id FROM " + KEPT
            + " ORDER BY similarity(dense, embed('refund', 'voyage:voyage-3')) DESC LIMIT 1"));
        assertTrue(wrong.getMessage().contains("is embedded with openai:text-embedding-3-small"),
            wrong.getMessage());
    }

    @Test
    void takesTheTextAndModelAsParameters() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT _id FROM " + NAME
            + " WHERE lang = ? ORDER BY similarity(keywords, embed(?, ?)) DESC LIMIT 10")) {
            statement.setString(1, "de");
            statement.setString(2, "refund policy");
            statement.setString(3, BM25);

            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("4", rows.getString("_id"));
            }
        }
    }

    @Test
    void explainsWhatAProviderAndAModelNeed() throws SQLException {
        SQLException hosted = assertThrows(SQLException.class, () -> ids("SELECT _id FROM " + NAME
            + " ORDER BY similarity(dense, embed('refund', "
            + "'qdrant:sentence-transformers/all-minilm-l6-v2')) DESC LIMIT 3"));
        assertTrue(hosted.getMessage().contains("runs no inference service"), hosted.getMessage());

        try (Connection noKeys = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties())) {
            SQLException missing = assertThrows(SQLException.class, () -> {
                try (Statement statement = noKeys.createStatement()) {
                    statement.executeQuery("SELECT _id FROM " + NAME + " ORDER BY similarity(dense, "
                        + "embed('refund', 'openai:text-embedding-3-small')) DESC LIMIT 3");
                }
            });
            assertEquals("Embedding with OpenAI needs its API key: set the openaiApiKey "
                + "connection property", missing.getMessage());
        }
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
    private static Object point(int id, String text, String lang, double x, double y) {
        return object("id", id,
            "vector", object(
                "dense", array(x, y),
                "keywords", object("text", text, "model", "qdrant/bm25")),
            "payload", object("text", text, "lang", lang));
    }
}
