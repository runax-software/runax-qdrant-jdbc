package com.runaxsoftware.qdrant.jdbc.embedding;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Voyage AI's embeddings API.
 *
 * <p>The role becomes Voyage's {@code input_type}, which prepends a retrieval prompt to the text:
 * a search query and a stored document get different ones, so that a short question lands near
 * the passages answering it. A model ID that asks for a size becomes {@code output_dimension},
 * which Voyage's newer models support.
 */
public final class VoyageAiEmbedder extends HttpEmbedder {

    public VoyageAiEmbedder(HttpClient http, URI baseUrl, String apiKey, Duration timeout) {
        super(http, baseUrl, apiKey, timeout, "Voyage AI");
    }

    @Override
    ObjectNode body(EmbeddingModel model, TextRole role, String text) {
        ObjectNode body = JSON.createObjectNode().put("model", model.name())
            .put("input_type", inputType(role));
        body.putArray("input").add(text);

        if (model.dimensions() > 0) body.put("output_dimension", model.dimensions());

        return body;
    }

    private static String inputType(TextRole role) {
        return switch (role) {
            case QUERY -> "query";
            case DOCUMENT -> "document";
        };
    }
}
