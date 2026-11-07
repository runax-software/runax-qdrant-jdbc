package com.runaxsoftware.qdrant.jdbc.embedding;

import java.sql.SQLException;
import java.util.List;

/**
 * Turns text into a dense vector with one provider's models, for {@code embed('...')} in SQL.
 *
 * <p>The whole model is passed rather than its name alone, so a provider can honour the size the
 * ID asks for, and the text's role comes with it: a search query and a document to store are the
 * same text to the driver and not to every model.
 */
public interface Embedder {

    /**
     * @param model the model, with the size it asks for, if any
     * @param role  whether the text is a search query or a document being stored
     * @param text  the text to embed
     */
    List<Double> embed(EmbeddingModel model, TextRole role, String text) throws SQLException;
}
