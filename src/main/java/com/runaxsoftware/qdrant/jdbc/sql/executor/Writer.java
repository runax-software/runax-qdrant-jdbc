package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Where write plans change what Qdrant holds: an interface rather than the gateway, so the
 * executor can be tested without a server.
 */
interface Writer {

    /**
     * How many points the filter matches.
     */
    long count(String collection, JsonNode filter) throws SQLException;

    /**
     * Deletes the points the filter matches.
     */
    void deletePoints(String collection, JsonNode filter) throws SQLException;

    /**
     * The IDs among these that the collection already has a point for.
     */
    List<JsonNode> existingIds(String collection, List<JsonNode> ids) throws SQLException;

    /**
     * Adds points, leaving any point already there as it is.
     */
    void insertPoints(String collection, List<JsonNode> points) throws SQLException;

    /**
     * Applies payload and vector changes, in order, in one batch.
     */
    void updatePoints(String collection, List<JsonNode> operations) throws SQLException;

    /**
     * The IDs of the points a filter matches, at most {@code limit + 1} of them, so the caller
     * can tell a capped read from a complete one.
     */
    List<JsonNode> matchingIds(String collection, JsonNode filter, int limit) throws SQLException;

    /**
     * Deletes the collection; fails with SQL state {@code 42S02} when there's none.
     */
    void deleteCollection(String collection) throws SQLException;

    /**
     * Whether the collection exists.
     */
    boolean collectionExists(String collection) throws SQLException;

    /**
     * Creates the collection with these vectors.
     */
    void createCollection(String collection, JsonNode config) throws SQLException;

    /**
     * Creates a payload index on the collection.
     */
    void createIndex(String collection, String field, JsonNode schema) throws SQLException;

    /**
     * The collection's payload indexes, by field, as Qdrant has them now.
     */
    Map<String, PayloadIndexType> payloadIndexes(String collection) throws SQLException;

    /**
     * Deletes the payload index on a field.
     */
    void deleteIndex(String collection, String field) throws SQLException;

    /**
     * Every alias, as Qdrant has them now.
     */
    List<QdrantAlias> aliases() throws SQLException;

    /**
     * Applies alias actions together, atomically.
     */
    void changeAliases(List<JsonNode> actions) throws SQLException;
}
