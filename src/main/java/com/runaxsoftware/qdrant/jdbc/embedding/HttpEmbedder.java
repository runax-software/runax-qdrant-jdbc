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
import java.util.List;

/**
 * An embedder behind an OpenAI-style HTTP API: a POST to {@code /embeddings} with a bearer key,
 * answered with {@code data[0].embedding}. OpenAI and Voyage AI both answer this way; each says
 * what its request holds.
 */
abstract class HttpEmbedder implements Embedder {

    static final ObjectMapper JSON = new ObjectMapper();

    private static final int MAX_ERROR_LENGTH = 300;

    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final String providerName;

    /**
     * @param baseUrl      the API's base URL, such as {@code https://api.openai.com/v1}
     * @param providerName the provider's name for error messages, such as {@code OpenAI}
     */
    HttpEmbedder(HttpClient http, URI baseUrl, String apiKey, Duration timeout, String providerName) {
        this.http = http;
        this.endpoint = URI.create(stripSlash(baseUrl.toString()) + "/embeddings");
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.providerName = providerName;
    }

    /**
     * The request body for one text.
     */
    abstract ObjectNode body(String model, String text);

    @Override
    public List<Double> embed(String model, String text) throws SQLException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body(model, text).toString()))
            .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new SQLTimeoutException(providerName + " didn't answer in time", "HYT00", e);
        } catch (IOException e) {
            throw new SQLException("Can't reach " + providerName + " at " + endpoint + ": "
                + e.getMessage(), "08001", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for " + providerName, "HY008", e);
        }

        return vector(response, model);
    }

    private List<Double> vector(HttpResponse<String> response, String model) throws SQLException {
        int status = response.statusCode();

        if (status == 401 || status == 403) {
            throw new SQLInvalidAuthorizationSpecException(providerName + " rejected the API key: "
                + error(response.body(), status), "28000", status);
        }
        if (status < 200 || status >= 300) {
            throw new SQLException(providerName + " couldn't embed with '" + model + "': "
                + error(response.body(), status), status < 500 ? "42000" : "HY000", status);
        }

        JsonNode embedding;
        try {
            embedding = JSON.readTree(response.body()).path("data").path(0).path("embedding");
        } catch (JsonProcessingException e) {
            throw new SQLException(providerName + " sent a response that isn't JSON", "08S01", e);
        }

        if (!embedding.isArray() || embedding.isEmpty()) {
            throw new SQLException(providerName + " sent no embedding for '" + model + "'", "08S01");
        }

        var vector = new ArrayList<Double>(embedding.size());
        for (JsonNode value : embedding) vector.add(value.asDouble());

        return vector;
    }

    // The provider's own message: error.message (OpenAI) or detail (Voyage), else the body
    private static String error(String body, int status) {
        if (body == null || body.isBlank()) return "HTTP " + status;

        try {
            JsonNode json = JSON.readTree(body);
            String message = json.path("error").path("message").asText(json.path("detail").asText(""));
            if (!message.isEmpty()) return message;
        } catch (JsonProcessingException e) {
            // Not JSON: the text below
        }

        String text = body.strip();
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) + "..." : text;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
