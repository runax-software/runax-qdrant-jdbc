package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;

import java.nio.file.Path;
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
     * Adds points, replacing any point already there when {@code replaceExisting}, and only
     * those the filter matches when it's given.
     */
    void insertPoints(String collection, List<JsonNode> points, boolean replaceExisting,
                      JsonNode updateFilter) throws SQLException;

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

    /**
     * Changes the collection's settings, or those of its vectors.
     */
    void updateCollection(String collection, JsonNode changes) throws SQLException;

    /**
     * The collection's vectors, as Qdrant has them now.
     */
    List<VectorField> vectors(String collection) throws SQLException;

    /**
     * Adds a named vector to the collection.
     */
    void createVector(String collection, String vector, JsonNode config) throws SQLException;

    /**
     * Deletes a named vector from the collection.
     */
    void deleteVector(String collection, String vector) throws SQLException;

    /**
     * Makes the one request a procedure is.
     */
    void call(HttpMethod method, String path, JsonNode body) throws SQLException;

    /**
     * Saves the file a procedure's request answers with to a new file on this machine.
     *
     * @return the bytes saved
     */
    long download(String path, Path file) throws SQLException;

    /**
     * Makes a procedure's request with a file on this machine as its body.
     */
    void upload(String path, Path file) throws SQLException;

    /**
     * The server's quota config, as Qdrant has it now.
     */
    JsonNode quotas() throws SQLException;

    /**
     * Replaces the server's quota config.
     */
    void setQuotas(JsonNode config) throws SQLException;

    /**
     * Forgets every issue Qdrant has reported about itself.
     */
    void clearIssues() throws SQLException;
}
