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
        List<Double> vector = openAi("sk-test").embed(openAiModel("text-embedding-3-small"), TextRole.QUERY, "refund policy");

        assertEquals(List.of(0.25, -0.5, 1.0), vector);
        assertEquals(List.of("Bearer sk-test"), authorizations);
        assertEquals(JSON.readTree("{\"model\": \"text-embedding-3-small\", \"input\": \"refund policy\"}"),
            bodies.get(0));
    }

    @Test
    void embedsWithVoyageAsAQuery() throws SQLException, IOException {
        new VoyageAiEmbedder(HttpClient.newHttpClient(), baseUrl, "pa-test", TIMEOUT)
            .embed(voyageModel("voyage-3"), TextRole.QUERY, "refund policy");

        assertEquals(List.of("Bearer pa-test"), authorizations);
        assertEquals(JSON.readTree("{\"model\": \"voyage-3\", \"input_type\": \"query\", "
            + "\"input\": [\"refund policy\"]}"), bodies.get(0));
    }

    @Test
    void reportsARejectedKey() {
        status = 401;
        answer = "{\"error\": {\"message\": \"Incorrect API key provided\"}}";

        SQLInvalidAuthorizationSpecException error = assertThrows(
            SQLInvalidAuthorizationSpecException.class, () -> openAi("sk-wrong").embed(openAiModel("m"), TextRole.QUERY, "x"));

        assertEquals("OpenAI rejected the API key: Incorrect API key provided", error.getMessage());
        assertFalse(error.getMessage().contains("sk-wrong"), "the key is never in a message");
    }

    @Test
    void reportsTheProvidersOwnMessage() {
        status = 400;
        answer = "{\"detail\": \"Model voyage-0 is not supported\"}";

        SQLException error = assertThrows(SQLException.class,
            () -> new VoyageAiEmbedder(HttpClient.newHttpClient(), baseUrl, "pa", TIMEOUT)
                .embed(voyageModel("voyage-0"), TextRole.QUERY, "x"));

        assertEquals("Voyage AI couldn't embed with 'voyage-0': Model voyage-0 is not supported",
            error.getMessage());
        assertEquals("42000", error.getSQLState());
    }

    @Test
    void reportsAnAnswerWithoutAnEmbedding() {
        answer = "{\"data\": []}";

        assertEquals("08S01", assertThrows(SQLException.class, () -> openAi("sk").embed(openAiModel("m"), TextRole.QUERY, "x"))
            .getSQLState());
    }

    @Test
    void reportsAProviderItCantReach() {
        server.stop(0);

        assertEquals("08001", assertThrows(SQLException.class, () -> openAi("sk").embed(openAiModel("m"), TextRole.QUERY, "x"))
            .getSQLState());
    }

    @Test
    void setsUpAProviderOnlyOnceItsKeyIsGiven() throws SQLException {
        var embedders = new ConnectionEmbedders(new ConnectionSettings.Embedding(
            "sk-test", baseUrl, null, baseUrl), TIMEOUT);

        assertEquals(List.of(0.25, -0.5, 1.0), embedders.embedder(Provider.OPENAI).embed(openAiModel("m"), TextRole.QUERY, "x"));

        SQLException missing = assertThrows(SQLException.class,
            () -> embedders.embedder(Provider.VOYAGE));
        assertEquals("Embedding with Voyage AI needs its API key: set the voyageApiKey connection "
            + "property", missing.getMessage());
        assertTrue(authorizations.size() == 1, "nothing was sent to Voyage");
    }

    private OpenAiEmbedder openAi(String key) {
        return new OpenAiEmbedder(HttpClient.newHttpClient(), baseUrl, key, TIMEOUT);
    }

    // What the providers are told beyond the text

    /**
     * A size in the model ID becomes each provider's own parameter, so the provider shortens the
     * vector rather than the driver truncating one it already paid for.
     */
    @Test
    void sendsTheSizeTheModelIdAsksFor() throws SQLException {
        openAi("sk-test").embed(new EmbeddingModel(Provider.OPENAI, "text-embedding-3-large", 1024),
            TextRole.QUERY, "refund policy");

        assertEquals(1024, bodies.get(0).path("dimensions").asInt(), bodies.get(0).toString());

        voyage("pa-test").embed(new EmbeddingModel(Provider.VOYAGE, "voyage-3-large", 512),
            TextRole.QUERY, "refund policy");

        assertEquals(512, bodies.get(1).path("output_dimension").asInt(), bodies.get(1).toString());
    }

    @Test
    void sendsNoSizeWhenTheModelIdAsksForNone() throws SQLException {
        openAi("sk-test").embed(openAiModel("text-embedding-3-small"), TextRole.QUERY, "x");
        voyage("pa-test").embed(voyageModel("voyage-3.5"), TextRole.QUERY, "x");

        assertTrue(bodies.get(0).path("dimensions").isMissingNode(), bodies.get(0).toString());
        assertTrue(bodies.get(1).path("output_dimension").isMissingNode(), bodies.get(1).toString());
    }

    /**
     * Voyage's models prepend a retrieval prompt that differs for a query and a document, so a
     * text being stored must not be sent as a query: that was the bug this signature fixes.
     */
    @Test
    void tellsVoyageWhetherTheTextIsAQueryOrADocument() throws SQLException {
        voyage("pa-test").embed(voyageModel("voyage-3.5"), TextRole.QUERY, "refund policy");
        voyage("pa-test").embed(voyageModel("voyage-3.5"), TextRole.DOCUMENT, "refund policy");

        assertEquals("query", bodies.get(0).path("input_type").asText());
        assertEquals("document", bodies.get(1).path("input_type").asText());
    }

    /**
     * OpenAI's models embed a query and a document the same way, so the role is nothing to send.
     */
    @Test
    void sendsOpenAiNoRole() throws SQLException {
        openAi("sk-test").embed(openAiModel("text-embedding-3-small"), TextRole.DOCUMENT, "x");

        assertTrue(bodies.get(0).path("input_type").isMissingNode(), bodies.get(0).toString());
    }

    private VoyageAiEmbedder voyage(String key) {
        return new VoyageAiEmbedder(HttpClient.newHttpClient(), baseUrl, key, TIMEOUT);
    }

    private static EmbeddingModel openAiModel(String name) {
        return new EmbeddingModel(Provider.OPENAI, name);
    }

    private static EmbeddingModel voyageModel(String name) {
        return new EmbeddingModel(Provider.VOYAGE, name);
    }
}
