package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;
import java.util.List;

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
     * Applies payload changes, in order, in one batch.
     */
    void updatePoints(String collection, List<JsonNode> operations) throws SQLException;

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
    void createIndex(String collection, String field, String type) throws SQLException;
}
