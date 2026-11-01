package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.runaxsoftware.qdrant.jdbc.embedding.EmbeddingModel;

/**
 * Text a search turns into its query vector, with the model that embeds it: Qdrant does that
 * itself for a {@code qdrant:} model, the driver calls the provider for any other.
 *
 * @param model      the model, which the statement named or the collection's metadata holds
 * @param text       the text to search with
 * @param dimensions the searched vector's dimensions, which a vector the driver gets back must
 *                   have; 0 when the vector has none of its own, as a sparse one
 */
public record EmbedText(EmbeddingModel model, String text, int dimensions) {
}
