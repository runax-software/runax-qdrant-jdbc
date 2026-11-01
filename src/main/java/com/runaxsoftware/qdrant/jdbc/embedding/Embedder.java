package com.runaxsoftware.qdrant.jdbc.embedding;

import java.sql.SQLException;
import java.util.List;

/**
 * Turns text into a dense vector with one provider's models, for a search's
 * {@code embed('...')}. The text is a query, which some models embed differently from the
 * documents they search.
 */
public interface Embedder {

    /**
     * @param model the model's name, as the provider knows it
     * @param text  the text to search with
     */
    List<Double> embed(String model, String text) throws SQLException;
}
