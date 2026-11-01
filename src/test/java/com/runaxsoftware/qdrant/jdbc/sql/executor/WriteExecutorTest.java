package com.runaxsoftware.qdrant.jdbc.sql.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DeletePointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdatePointsPlan;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

class WriteExecutorTest {

    private static final JsonNode FILTER = Json.object("must", Json.array(Json.object("has_id", Json.array(1))));

    private final List<String> calls = new ArrayList<>();
    private long matching = 3;
    private SQLException dropFailure;
    private boolean exists;
    private String failingIndex;

    private final Executor executor = new Executor(
        request -> {
            throw new AssertionError("Scrolled");
        },
        query -> {
            throw new AssertionError("Searched");
        },
        (collection, filter) -> {
            throw new AssertionError("Counted " + collection);
        },
        facet -> {
            throw new AssertionError("Counted the values of " + facet.key());
        },
        table -> {
            throw new AssertionError("Read " + table);
        },
        new Writer() {
            @Override
            public long count(String collection, JsonNode filter) {
                calls.add("count " + collection + " " + filter);
                return matching;
            }

            @Override
            public void deletePoints(String collection, JsonNode filter) {
                calls.add("delete " + collection + " " + filter);
            }

            @Override
            public void updatePoints(String collection, List<JsonNode> operations) {
                calls.add("update " + collection + " " + operations);
            }

            @Override
            public void deleteCollection(String collection) throws SQLException {
                calls.add("drop " + collection);
                if (dropFailure != null) throw dropFailure;
            }

            @Override
            public boolean collectionExists(String collection) {
                calls.add("exists " + collection);
                return exists;
            }

            @Override
            public void createCollection(String collection, JsonNode config) {
                calls.add("create " + collection + " " + config);
            }

            @Override
            public void createIndex(String collection, String field, String type)
                throws SQLException {
                calls.add("index " + collection + " " + field + " " + type);
                if (field.equals(failingIndex)) throw new SQLException("Qdrant: bad index", "42000");
            }
        });

    @Test
    void countsThePointsThenDeletesThem() throws SQLException {
        ExecutionResult result = executor.execute(new DeletePointsPlan("docs", FILTER), statement());

        assertFalse(result.hasResultSet());
        assertEquals(3, result.updateCount());
        assertEquals(List.of("count docs " + FILTER, "delete docs " + FILTER), calls);
    }

    @Test
    void sendsNoDeleteWhenNothingMatches() throws SQLException {
        matching = 0;

        assertEquals(0, executor.execute(new DeletePointsPlan("docs", FILTER), statement())
            .updateCount());
        assertEquals(List.of("count docs " + FILTER), calls);
    }

    @Test
    void dropsACollection() throws SQLException {
        ExecutionResult result = executor.execute(new DropCollectionPlan("docs", false), statement());

        assertEquals(0, result.updateCount());
        assertEquals(List.of("drop docs"), calls);
    }

    @Test
    void reportsAMissingCollectionUnlessIfExists() throws SQLException {
        dropFailure = new SQLException("Collection `docs` doesn't exist", "42S02");

        assertEquals("42S02", assertThrows(SQLException.class,
            () -> executor.execute(new DropCollectionPlan("docs", false), statement())).getSQLState());
        assertEquals(0, executor.execute(new DropCollectionPlan("docs", true), statement())
            .updateCount());
    }

    @Test
    void reportsOtherFailuresEvenWithIfExists() {
        dropFailure = new SQLException("Qdrant: forbidden", "28000");

        assertEquals("28000", assertThrows(SQLException.class,
            () -> executor.execute(new DropCollectionPlan("docs", true), statement())).getSQLState());
    }

    @Test
    void countsThePointsThenChangesThemInOneBatch() throws SQLException {
        List<JsonNode> operations = List.of(Json.object("set_payload",
            Json.object("payload", Json.object("lang", "de"), "filter", FILTER)));

        ExecutionResult result = executor.execute(new UpdatePointsPlan("docs", FILTER, operations),
            statement());

        assertEquals(3, result.updateCount());
        assertEquals(List.of("count docs " + FILTER, "update docs " + operations), calls);
    }

    @Test
    void sendsNoUpdateWhenNothingMatches() throws SQLException {
        matching = 0;

        assertEquals(0, executor.execute(new UpdatePointsPlan("docs", FILTER, List.of()), statement())
            .updateCount());
        assertEquals(List.of("count docs " + FILTER), calls);
    }

    // CREATE TABLE

    private static final JsonNode CONFIG = Json.object("vectors", Json.object("size", 2, "distance", "Cosine"));

    private static CreateCollectionPlan create(boolean ifNotExists) {
        return new CreateCollectionPlan("docs", ifNotExists, CONFIG, List.of(
            new CreateCollectionPlan.PayloadIndex("lang", "keyword"),
            new CreateCollectionPlan.PayloadIndex("year", "integer")));
    }

    @Test
    void createsTheCollectionThenItsIndexes() throws SQLException {
        assertEquals(0, executor.execute(create(false), statement()).updateCount());
        assertEquals(List.of("create docs " + CONFIG, "index docs lang keyword",
            "index docs year integer"), calls);
    }

    @Test
    void createsNothingWhenTheCollectionExistsAndIfNotExists() throws SQLException {
        exists = true;

        executor.execute(create(true), statement());

        assertEquals(List.of("exists docs"), calls);
    }

    @Test
    void deletesTheCollectionAgainWhenAnIndexFails() {
        failingIndex = "year";

        assertEquals("42000", assertThrows(SQLException.class,
            () -> executor.execute(create(false), statement())).getSQLState());
        assertEquals(List.of("create docs " + CONFIG, "index docs lang keyword",
            "index docs year integer", "drop docs"), calls);
    }

    private static Statement statement() {
        return (Statement) Proxy.newProxyInstance(WriteExecutorTest.class.getClassLoader(),
            new Class<?>[] {Statement.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "getLargeMaxRows" -> 0L;
                case "getFetchSize" -> 0;
                default -> throw new AssertionError("Called Statement." + method.getName());
            });
    }
}
