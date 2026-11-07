package com.runaxsoftware.qdrant.jdbc.embedding;

import java.sql.SQLException;
import java.util.List;

/**
 * Turns text into dense vectors with one provider's models, for {@code embed('...')} in SQL.
 *
 * <p>A batch of texts is embedded at a time. One statement often needs many: an
 * {@code INSERT} of a thousand rows that embeds a column has a thousand texts, and the
 * providers take them all in one request, so asking for them one at a time would be a thousand
 * round trips. An implementation may still split a batch to stay within its provider's limits.
 *
 * <p>The whole model is passed rather than its name alone, so a provider can honour the size the
 * ID asks for, and the text's role comes with it: a search query and a document to store are the
 * same text to the driver and not to every model.
 */
public interface Embedder {

    /**
     * Embeds a batch of texts.
     *
     * @param model the model, with the size it asks for, if any
     * @param role  whether the texts are search queries or documents being stored
     * @param texts the texts, in order; may be empty
     * @return one vector per text, in the order of the texts
     */
    List<List<Double>> embed(EmbeddingModel model, TextRole role, List<String> texts)
        throws SQLException;

    /**
     * Embeds one text, for the statements that have only one: a search, and an {@code UPDATE},
     * which writes the same vector to every point its {@code WHERE} matches.
     */
    default List<Double> embed(EmbeddingModel model, TextRole role, String text)
        throws SQLException {
        return embed(model, role, List.of(text)).get(0);
    }
}
