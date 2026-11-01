package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;

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
     * Deletes the collection; fails with SQL state {@code 42S02} when there's none.
     */
    void deleteCollection(String collection) throws SQLException;
}
