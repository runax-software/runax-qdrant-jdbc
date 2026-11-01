package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;

/**
 * Counts a collection's points: {@code gateway::count} when running, or a number given by hand
 * in tests.
 */
@FunctionalInterface
interface Counter {

    long count(String collection, JsonNode filter) throws SQLException;
}
