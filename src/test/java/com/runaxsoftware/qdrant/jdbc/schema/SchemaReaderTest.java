package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.Endpoint;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class SchemaReaderTest {

    private FakeQdrantServer server;
    private QdrantGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = FakeQdrantServer.start()
            .collection("products", FakeQdrantServer.unnamedVector(4, "Cosine",
                Map.of("name", "keyword")), List.of(
                FakeQdrantServer.point(1, Json.object("name", "Lamp", "price", 19.5),
                    Json.array(0.1, 0.2, 0.3, 0.4)),
                FakeQdrantServer.point(2, Json.object("name", "Desk", "stock", 3))));
        gateway = QdrantGateway.open("localhost", server.port(), false, null, Duration.ofSeconds(5),
            "test");
    }

    @AfterEach
    void stop() {
        gateway.close();
        server.close();
    }

    @Test
    void readsTheCollectionAndASampleOfPoints() throws SQLException {
        TableSchema schema = new SchemaReader(gateway, 50).read("products");

        assertEquals("products", schema.name());
        assertEquals("_id name price stock _payload _vector", names(schema));
        assertEquals(42, schema.collection().pointsCount());
        assertEquals(PayloadIndexType.KEYWORD, schema.collection().payloadIndexes().get("name"));
        assertEquals("Payload field (keyword index)",
            schema.column("name").orElseThrow().remarks());
    }

    @Test
    void asksForPayloadsButNotVectors() throws SQLException {
        new SchemaReader(gateway, 50).read("products");

        assertEquals(Json.object("limit", 50, "with_payload", true, "with_vector", false),
            server.scrollRequests().get(0));
        assertEquals("/collections/products/points/scroll", server.requests().get(1).path());
    }

    @Test
    void readsOnlyOnePage() throws SQLException {
        TableSchema schema = new SchemaReader(gateway, 1).read("products");

        assertEquals("_id name price _payload _vector", names(schema));
        assertEquals(1, server.scrollRequests().size());
    }

    @Test
    void usesPayloadIndexesAloneWithoutASample() throws SQLException {
        TableSchema schema = new SchemaReader(gateway, 0).read("products");

        assertEquals("_id name _payload _vector", names(schema));
        assertTrue(server.scrollRequests().isEmpty());
        assertEquals(0, server.calls(Endpoint.SCROLL));
    }

    @Test
    void asksQdrantAgainEveryTime() throws SQLException {
        SchemaReader reader = new SchemaReader(gateway, 10);

        reader.read("products");
        reader.read("products");

        assertEquals(2, server.calls(Endpoint.COLLECTION));
        assertEquals(2, server.calls(Endpoint.SCROLL));
    }

    @Test
    void reportsAMissingCollection() {
        SQLException error = assertThrows(SQLException.class,
            () -> new SchemaReader(gateway, 10).read("missing"));

        assertEquals("42S02", error.getSQLState());
        assertEquals(0, server.calls(Endpoint.SCROLL));
    }

    @Test
    void reportsACollectionDeletedBetweenTheTwoCalls() {
        server.fail(Endpoint.SCROLL, 404, "Not found: Collection `products` doesn't exist!");

        SQLException error = assertThrows(SQLException.class,
            () -> new SchemaReader(gateway, 10).read("products"));

        assertEquals("42S02", error.getSQLState());
    }

    private static String names(TableSchema schema) {
        return schema.columns().stream().map(ColumnDefinition::name)
            .collect(Collectors.joining(" "));
    }
}
