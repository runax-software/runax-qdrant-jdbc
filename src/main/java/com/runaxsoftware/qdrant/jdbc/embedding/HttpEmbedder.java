package com.runaxsoftware.qdrant.jdbc.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An embedder behind an OpenAI-style HTTP API: a POST to {@code /embeddings} with a bearer key,
 * answered with {@code {"data": [{"index": 0, "embedding": [...]}, ...]}}. OpenAI and Voyage AI
 * both answer this way; each says what its request holds and what its limits are.
 *
 * <p>A batch is split into as many requests as the provider's limits need, and the answers are
 * put back in the order of the texts: a provider may return them in any order, saying which is
 * which in each item's {@code index}.
 *
 * <p>No failure here reports a connection SQLState ({@code 08...}). The connection to Qdrant is
 * fine when a provider is unreachable, and a pool that sees {@code 08} closes the connection
 * that reported it.
 */
abstract class HttpEmbedder implements Embedder {

    static final ObjectMapper JSON = new ObjectMapper();

    private static final int MAX_ERROR_LENGTH = 300;
    private static final String REDACTED = "***";

    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final String providerName;
    private final int maxTexts;
    private final int maxTokens;

    /**
     * @param baseUrl      the API's base URL, such as {@code https://api.openai.com/v1}
     * @param providerName the provider's name for error messages, such as {@code OpenAI}
     * @param maxTexts     the most texts the provider takes in one request
     * @param maxTokens    the most tokens it takes in one request, over all its texts
     */
    HttpEmbedder(HttpClient http, URI baseUrl, String apiKey, Duration timeout,
                 String providerName, int maxTexts, int maxTokens) {
        this.http = http;
        this.endpoint = URI.create(stripSlash(baseUrl.toString()) + "/embeddings");
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.providerName = providerName;
        this.maxTexts = maxTexts;
        this.maxTokens = maxTokens;
    }

    /**
     * The request body for one batch of texts, as the provider takes it.
     */
    abstract ObjectNode body(EmbeddingModel model, TextRole role, List<String> texts);

    @Override
    public List<List<Double>> embed(EmbeddingModel model, TextRole role, List<String> texts)
        throws SQLException {
        var vectors = new ArrayList<List<Double>>(texts.size());

        for (List<String> batch : batches(texts, maxTexts, maxTokens)) {
            vectors.addAll(vectors(send(model, role, batch), model, batch.size()));
        }

        return List.copyOf(vectors);
    }

    /**
     * Splits texts into consecutive batches of at most {@code maxTexts} texts and, by
     * {@link #estimateTokens}, at most {@code maxTokens} tokens. A text over the token limit on
     * its own gets a batch of its own, for the provider to truncate or reject.
     */
    static List<List<String>> batches(List<String> texts, int maxTexts, int maxTokens) {
        var batches = new ArrayList<List<String>>();
        int start = 0;
        long tokens = 0;

        for (int i = 0; i < texts.size(); i++) {
            long estimate = estimateTokens(texts.get(i));

            if (i - start == maxTexts || (i > start && tokens + estimate > maxTokens)) {
                batches.add(texts.subList(start, i));
                start = i;
                tokens = 0;
            }

            tokens += estimate;
        }

        if (start < texts.size()) batches.add(texts.subList(start, texts.size()));

        return batches;
    }

    /**
     * Estimates a text's tokens as its UTF-8 bytes. A token of the byte-level tokenizers these
     * providers use stands for at least one byte, so the estimate runs high — about four times
     * for English — which keeps a batch inside the token limit at the cost of a few more calls.
     */
    static long estimateTokens(String text) {
        long bytes = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;  // each half of a surrogate pair counts 3
        }

        return bytes;
    }

    private HttpResponse<String> send(EmbeddingModel model, TextRole role, List<String> texts)
        throws SQLException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body(model, role, texts).toString(),
                StandardCharsets.UTF_8))
            .build();

        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new SQLTimeoutException(providerName + " didn't answer in time", "HYT00", e);
        } catch (IOException e) {
            throw new SQLException("Can't reach " + providerName + " to embed with " + model + ": "
                + redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                "HY000", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for " + providerName, "HY008", e);
        }
    }

    /**
     * Reads {@code data} into one vector per text of the batch, each put where its own
     * {@code index} says, or at its position when it gives none.
     */
    private List<List<Double>> vectors(HttpResponse<String> response, EmbeddingModel model,
                                       int expected) throws SQLException {
        int status = response.statusCode();

        if (status == 401 || status == 403) {
            throw new SQLInvalidAuthorizationSpecException(providerName + " rejected the API key: "
                + error(response.body(), status), "28000", status);
        }
        if (status < 200 || status >= 300) {
            throw new SQLException(providerName + " couldn't embed with '" + model + "': "
                + error(response.body(), status), status < 500 ? "42000" : "HY000", status);
        }

        JsonNode data;
        try {
            data = JSON.readTree(response.body()).path("data");
        } catch (JsonProcessingException e) {
            throw new SQLException(providerName + " sent a response that isn't JSON", "HY000", e);
        }

        if (!data.isArray() || data.size() != expected) {
            throw new SQLException(providerName + " sent " + (data.isArray() ? data.size() : 0)
                + " embeddings for " + expected + (expected == 1 ? " text" : " texts")
                + " with '" + model + "'", "HY000");
        }

        var vectors = new ArrayList<List<Double>>(Collections.nCopies(expected, null));

        for (int position = 0; position < expected; position++) {
            JsonNode item = data.get(position);
            int index = item.path("index").asInt(position);

            if (index < 0 || index >= expected || vectors.get(index) != null) {
                throw new SQLException(providerName + " sent embedding index " + index
                    + ", which doesn't fit " + expected + " texts, with '" + model + "'", "HY000");
            }

            vectors.set(index, vector(item.path("embedding"), model));
        }

        return vectors;
    }

    private List<Double> vector(JsonNode embedding, EmbeddingModel model) throws SQLException {
        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new SQLException(providerName + " sent no embedding for '" + model + "'", "HY000");
        }

        var vector = new ArrayList<Double>(embedding.size());
        for (JsonNode value : embedding) vector.add(value.asDouble());

        return vector;
    }

    // The provider's own message: error.message (OpenAI) or detail (Voyage), else the body
    private String error(String body, int status) {
        if (body == null || body.isBlank()) return "HTTP " + status;

        try {
            JsonNode json = JSON.readTree(body);
            String message = json.path("error").path("message").asText(json.path("detail").asText(""));
            if (!message.isEmpty()) return cut(redact(message));
        } catch (JsonProcessingException e) {
            // Not JSON: the text below
        }

        return cut(redact(body));
    }

    /**
     * Replaces the API key wherever it appears, in case a provider or a proxy repeats it back.
     */
    private String redact(String text) {
        return apiKey == null || text == null ? text : text.replace(apiKey, REDACTED);
    }

    private static String cut(String text) {
        String trimmed = text.strip();
        return trimmed.length() > MAX_ERROR_LENGTH
            ? trimmed.substring(0, MAX_ERROR_LENGTH) + "..." : trimmed;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
