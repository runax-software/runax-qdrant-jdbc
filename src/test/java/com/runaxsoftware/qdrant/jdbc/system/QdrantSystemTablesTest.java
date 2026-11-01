package com.runaxsoftware.qdrant.jdbc.system;

import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.collectionInfo;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.dense;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.unnamedVector;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.Endpoint;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class QdrantSystemTablesTest {

    private FakeQdrantServer server;
    private QdrantGateway gateway;
    private QdrantSystemTables tables;

    @BeforeEach
    void start() throws Exception {
        server = FakeQdrantServer.start();
        gateway = QdrantGateway.open("localhost", server.port(), false, null,
            Duration.ofSeconds(5), "test-agent/1.0");
        tables = new QdrantSystemTables(gateway);
    }

    @AfterEach
    void stop() {
        gateway.close();
        server.close();
    }

    // Collections

    @Test
    void readsEveryCollectionSortedByName() throws SQLException {
        server.collection("products", unnamedVector(4, "Cosine", Map.of()), List.of())
            .collection("Mixed-Case", unnamedVector(2, "Dot", Map.of()), List.of())
            .collection("articles", unnamedVector(3, "Euclid", Map.of()), List.of());

        assertEquals(List.of("Mixed-Case", "articles", "products"), names(SystemTable.COLLECTIONS));
    }

    @Test
    void readsACollectionsStateAndParameters() throws SQLException {
        server.collection("docs", collectionInfo(Json.object("dense", dense(3, "Euclid")),
            Json.object("keywords", Json.object()), Map.of("lang", "keyword")), List.of());

        assertArrayEquals(new Object[]{
            "docs",
            "green",
            "ok",
            42L,
            null,                                               // Qdrant sends no indexed count
            1L,
            1L,
            1L,
            null,                                               // nor a write consistency factor
            true,
            "{\"dense\":{\"size\":3,\"distance\":\"Euclid\"}}",
            "{\"keywords\":{}}",
            "{\"lang\":{\"data_type\":\"keyword\",\"points\":42}}",
        }, row(SystemTable.COLLECTIONS, "docs"));
    }

    @Test
    void readsAnObjectStatusAsItsJson() throws SQLException {
        server.collection("docs", collectionInfo(dense(2, "Cosine"), null, Map.of())
            .set("optimizer_status", Json.object("error", "No space left")), List.of());

        assertEquals("{\"error\":\"No space left\"}",
            value(SystemTable.COLLECTIONS, "docs", "optimizer_status"));
    }

    @Test
    void readsMissingFieldsAsNull() throws SQLException {
        server.collection("bare", Json.object(), List.of());

        Object[] row = row(SystemTable.COLLECTIONS, "bare");

        assertEquals("bare", row[0]);
        for (int i = 1; i < row.length; i++) {
            assertEquals(null, row[i], SystemTable.COLLECTIONS.columns().get(i).name());
        }
    }

    @Test
    void leavesOutACollectionDeletedSinceTheListing() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .listedButMissing("ghost");

        assertEquals(List.of("docs"), names(SystemTable.COLLECTIONS));
    }

    @Test
    void readsOneCollectionAtATime() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .collection("notes", unnamedVector(2, "Cosine", Map.of()), List.of());

        tables.read(SystemTable.COLLECTIONS);

        assertEquals(1, server.calls(Endpoint.COLLECTIONS));
        assertEquals(2, server.calls(Endpoint.COLLECTION));
    }

    @Test
    void readsNoRowsFromAServerWithoutCollections() throws SQLException {
        assertEquals(List.of(), tables.read(SystemTable.COLLECTIONS));
        assertEquals(List.of(), tables.read(SystemTable.ALIASES));
    }

    @Test
    void reportsWhatQdrantReportsAboutTheListing() {
        server.fail(Endpoint.COLLECTIONS, 500, "Service internal error");

        SQLException error = assertThrows(SQLException.class,
            () -> tables.read(SystemTable.COLLECTIONS));

        assertEquals("HY000", error.getSQLState());
        assertTrue(error.getMessage().contains("Service internal error"), error.getMessage());
    }

    @Test
    void reportsWhatQdrantReportsAboutACollection() {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .fail(Endpoint.COLLECTION, 500, "Service internal error");

        SQLException error = assertThrows(SQLException.class,
            () -> tables.read(SystemTable.COLLECTIONS));

        assertEquals("HY000", error.getSQLState());
    }

    // Aliases

    @Test
    void readsEveryAliasSortedByName() throws SQLException {
        server.collection("docs", unnamedVector(2, "Cosine", Map.of()), List.of())
            .collection("notes", unnamedVector(2, "Cosine", Map.of()), List.of())
            .alias("current", "docs")
            .alias("Best", "notes");

        List<Object[]> rows = tables.read(SystemTable.ALIASES);

        assertEquals(2, rows.size());
        assertArrayEquals(new Object[]{"Best", "notes"}, rows.get(0));
        assertArrayEquals(new Object[]{"current", "docs"}, rows.get(1));
    }

    // Helpers

    private List<String> names(SystemTable table) throws SQLException {
        List<String> names = new ArrayList<>();
        for (Object[] row : tables.read(table)) names.add((String) row[0]);

        return names;
    }

    private Object[] row(SystemTable table, String name) throws SQLException {
        return tables.read(table).stream()
            .filter(row -> name.equals(row[0]))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No row for " + name));
    }

    private Object value(SystemTable table, String name, String column) throws SQLException {
        return row(table, name)[table.indexOf(table.column(column).orElseThrow())];
    }
}
