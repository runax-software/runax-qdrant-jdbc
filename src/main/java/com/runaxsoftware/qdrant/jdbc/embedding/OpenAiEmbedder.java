package com.runaxsoftware.qdrant.jdbc.embedding;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * OpenAI's embeddings API, or any API that answers like it (Azure OpenAI through its base URL,
 * or a local server that copies it).
 */
public final class OpenAiEmbedder extends HttpEmbedder {

    public OpenAiEmbedder(HttpClient http, URI baseUrl, String apiKey, Duration timeout) {
        super(http, baseUrl, apiKey, timeout, "OpenAI");
    }

    @Override
    ObjectNode body(String model, String text) {
        return JSON.createObjectNode().put("model", model).put("input", text);
    }
}
