package com.runaxsoftware.qdrant.jdbc.embedding;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Voyage AI's embeddings API. The text is sent as a query, which Voyage's models embed for
 * searching documents.
 */
public final class VoyageAiEmbedder extends HttpEmbedder {

    public VoyageAiEmbedder(HttpClient http, URI baseUrl, String apiKey, Duration timeout) {
        super(http, baseUrl, apiKey, timeout, "Voyage AI");
    }

    @Override
    ObjectNode body(String model, String text) {
        ObjectNode body = JSON.createObjectNode().put("model", model).put("input_type", "query");
        body.putArray("input").add(text);

        return body;
    }
}
