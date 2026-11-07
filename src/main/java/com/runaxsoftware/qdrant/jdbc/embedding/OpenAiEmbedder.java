package com.runaxsoftware.qdrant.jdbc.embedding;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * OpenAI's embeddings API, or any API that answers like it (Azure OpenAI through its base URL,
 * or a local server that copies it).
 *
 * <p>OpenAI's models embed a query and a document the same way, so the role is ignored. A model
 * ID that asks for a size becomes {@code dimensions}, which the {@code text-embedding-3} models
 * support; an older model rejects it, which is OpenAI's error to report rather than the driver's
 * to guess at.
 */
public final class OpenAiEmbedder extends HttpEmbedder {

    public OpenAiEmbedder(HttpClient http, URI baseUrl, String apiKey, Duration timeout) {
        super(http, baseUrl, apiKey, timeout, "OpenAI");
    }

    @Override
    ObjectNode body(EmbeddingModel model, TextRole role, String text) {
        ObjectNode body = JSON.createObjectNode().put("model", model.name()).put("input", text);

        if (model.dimensions() > 0) body.put("dimensions", model.dimensions());

        return body;
    }
}
