package com.runaxsoftware.qdrant.jdbc.gateway.models;

/**
 * Text for Qdrant to turn into a vector itself, with one of the models it runs: server-side
 * inference, so the driver embeds nothing. Qdrant Cloud runs hosted models; every Qdrant runs
 * {@code qdrant/bm25}, which makes sparse vectors.
 *
 * @param text  the text to embed
 * @param model the model's name, as Qdrant knows it, such as {@code qdrant/bm25}
 */
public record Document(String text, String model) {
}
