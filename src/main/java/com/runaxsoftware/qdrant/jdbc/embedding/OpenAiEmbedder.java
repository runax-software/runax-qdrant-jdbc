package com.runaxsoftware.qdrant.jdbc.embedding;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

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

    /**
     * The most texts in one request: OpenAI's limit on {@code input}'s length.
     */
    static final int MAX_TEXTS = 2048;

    /**
     * The most tokens in one request, summed over its texts: OpenAI's limit for every embedding
     * model.
     */
    static final int MAX_TOKENS = 300_000;

    public OpenAiEmbedder(HttpClient http, URI baseUrl, String apiKey, Duration timeout) {
        super(http, baseUrl, apiKey, timeout, "OpenAI", MAX_TEXTS, MAX_TOKENS);
    }

    @Override
    ObjectNode body(EmbeddingModel model, TextRole role, List<String> texts) {
        ObjectNode body = JSON.createObjectNode().put("model", model.name());
        ArrayNode input = body.putArray("input");

        for (String text : texts) input.add(text);

        if (model.dimensions() > 0) body.put("dimensions", model.dimensions());

        return body;
    }
}
