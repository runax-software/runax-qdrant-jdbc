package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;

import java.util.List;

/**
 * Rank a collection's points through Qdrant's Query API: by how close they are to a vector, or
 * by how well a payload field's text matches a query. One call answers the whole search, so
 * unlike a scroll it reads no cursor; the limit says how many points Qdrant ranks.
 *
 * @param collection  the collection to search
 * @param columns     the result set's columns, in SELECT order, which may include the score
 * @param vector      the name of the vector to search, empty for a collection's single unnamed
 *                    vector; for a search by text, the payload field to rank by, as Qdrant
 *                    names it
 * @param query       the vector to search with, of that vector's dimensions; empty when the
 *                    search embeds text or ranks by text instead
 * @param withPayload whether any column needs the payload
 * @param withVector  whether any column needs a vector
 * @param limit       the maximum number of rows, or {@code null} when the statement gave none
 *                    and the statement's maximum rows decide
 * @param offset      how many of the best-ranked points to pass over, for an OFFSET, or 0 for
 *                    none. The Query API's {@code offset} is a row count, unlike a scroll's, so
 *                    this one goes to Qdrant and the driver discards nothing.
 * @param filter      the statement's WHERE as a Qdrant filter, or {@code null} when it has none
 * @param embed       text to embed and search with, or {@code null} when the query vector is
 *                    written out
 * @param text        the words to rank a payload field's text by, with BM25 over its text
 *                    index, or {@code null} for a search of a vector
 */
public record QueryPlan(
    String collection,
    List<ColumnDefinition> columns,
    String vector,
    List<Double> query,
    boolean withPayload,
    boolean withVector,
    Long limit,
    long offset,
    JsonNode filter,
    EmbedText embed,
    String text
) implements Plan {

    public QueryPlan(String collection, List<ColumnDefinition> columns, String vector,
                     List<Double> query, boolean withPayload, boolean withVector, Long limit,
                     long offset, JsonNode filter, EmbedText embed, String text) {
        this.collection = collection;
        this.columns = List.copyOf(columns);
        this.vector = vector;
        this.query = List.copyOf(query);
        this.withPayload = withPayload;
        this.withVector = withVector;
        this.limit = limit;
        this.offset = offset;
        this.filter = filter;
        this.embed = embed;
        this.text = text;
    }

    /**
     * A search of a vector, with one written out or with text to embed.
     */
    public QueryPlan(String collection, List<ColumnDefinition> columns, String vector,
                     List<Double> query, boolean withPayload, boolean withVector, Long limit,
                     long offset, JsonNode filter, EmbedText embed) {
        this(collection, columns, vector, query, withPayload, withVector, limit, offset, filter,
            embed, null);
    }

    /**
     * A search with a vector written out.
     */
    public QueryPlan(String collection, List<ColumnDefinition> columns, String vector,
                     List<Double> query, boolean withPayload, boolean withVector, Long limit,
                     long offset, JsonNode filter) {
        this(collection, columns, vector, query, withPayload, withVector, limit, offset, filter,
            null);
    }

    /**
     * A search that starts at the closest point, which is every search without an OFFSET.
     */
    public QueryPlan(String collection, List<ColumnDefinition> columns, String vector,
                     List<Double> query, boolean withPayload, boolean withVector, Long limit,
                     JsonNode filter, EmbedText embed) {
        this(collection, columns, vector, query, withPayload, withVector, limit, 0, filter, embed);
    }

    /**
     * A search with a vector written out, from the closest point on.
     */
    public QueryPlan(String collection, List<ColumnDefinition> columns, String vector,
                     List<Double> query, boolean withPayload, boolean withVector, Long limit,
                     JsonNode filter) {
        this(collection, columns, vector, query, withPayload, withVector, limit, 0, filter, null);
    }

    /**
     * A search of every point, unfiltered.
     */
    public QueryPlan(String collection, List<ColumnDefinition> columns, String vector,
                     List<Double> query, boolean withPayload, boolean withVector, Long limit) {
        this(collection, columns, vector, query, withPayload, withVector, limit, 0, null, null);
    }
}
