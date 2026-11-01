package com.runaxsoftware.qdrant.jdbc.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runaxsoftware.qdrant.jdbc.connection.ConnectionSettings;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The OpenAI and Voyage AI embedders against a server that answers as their APIs do.
 */
class HttpEmbedderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private HttpServer server;
    private URI baseUrl;
    private final List<String> authorizations = new ArrayList<>();
    private final List<JsonNode> bodies = new ArrayList<>();
    private int status = 200;
    private String answer = "{\"data\": [{\"embedding\": [0.25, -0.5, 1]}]}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(JSON.readTree(exchange.getRequestBody()));

            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void embedsWithOpenAi() throws SQLException, IOException {
        List<Double> vector = openAi("sk-test").embed("text-embedding-3-small", "refund policy");

        assertEquals(List.of(0.25, -0.5, 1.0), vector);
        assertEquals(List.of("Bearer sk-test"), authorizations);
        assertEquals(JSON.readTree("{\"model\": \"text-embedding-3-small\", \"input\": \"refund policy\"}"),
            bodies.get(0));
    }

    @Test
    void embedsWithVoyageAsAQuery() throws SQLException, IOException {
        new VoyageAiEmbedder(HttpClient.newHttpClient(), baseUrl, "pa-test", TIMEOUT)
            .embed("voyage-3", "refund policy");

        assertEquals(List.of("Bearer pa-test"), authorizations);
        assertEquals(JSON.readTree("{\"model\": \"voyage-3\", \"input_type\": \"query\", "
            + "\"input\": [\"refund policy\"]}"), bodies.get(0));
    }

    @Test
    void reportsARejectedKey() {
        status = 401;
        answer = "{\"error\": {\"message\": \"Incorrect API key provided\"}}";

        SQLInvalidAuthorizationSpecException error = assertThrows(
            SQLInvalidAuthorizationSpecException.class, () -> openAi("sk-wrong").embed("m", "x"));

        assertEquals("OpenAI rejected the API key: Incorrect API key provided", error.getMessage());
        assertFalse(error.getMessage().contains("sk-wrong"), "the key is never in a message");
    }

    @Test
    void reportsTheProvidersOwnMessage() {
        status = 400;
        answer = "{\"detail\": \"Model voyage-0 is not supported\"}";

        SQLException error = assertThrows(SQLException.class,
            () -> new VoyageAiEmbedder(HttpClient.newHttpClient(), baseUrl, "pa", TIMEOUT)
                .embed("voyage-0", "x"));

        assertEquals("Voyage AI couldn't embed with 'voyage-0': Model voyage-0 is not supported",
            error.getMessage());
        assertEquals("42000", error.getSQLState());
    }

    @Test
    void reportsAnAnswerWithoutAnEmbedding() {
        answer = "{\"data\": []}";

        assertEquals("08S01", assertThrows(SQLException.class, () -> openAi("sk").embed("m", "x"))
            .getSQLState());
    }

    @Test
    void reportsAProviderItCantReach() {
        server.stop(0);

        assertEquals("08001", assertThrows(SQLException.class, () -> openAi("sk").embed("m", "x"))
            .getSQLState());
    }

    @Test
    void setsUpAProviderOnlyOnceItsKeyIsGiven() throws SQLException {
        var embedders = new ConnectionEmbedders(new ConnectionSettings.Embedding(
            "sk-test", baseUrl, null, baseUrl), TIMEOUT);

        assertEquals(List.of(0.25, -0.5, 1.0), embedders.embedder(Provider.OPENAI).embed("m", "x"));

        SQLException missing = assertThrows(SQLException.class,
            () -> embedders.embedder(Provider.VOYAGE));
        assertEquals("Embedding with Voyage AI needs its API key: set the voyageApiKey connection "
            + "property", missing.getMessage());
        assertTrue(authorizations.size() == 1, "nothing was sent to Voyage");
    }

    private OpenAiEmbedder openAi(String key) {
        return new OpenAiEmbedder(HttpClient.newHttpClient(), baseUrl, key, TIMEOUT);
    }
}
