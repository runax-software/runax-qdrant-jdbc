package com.runaxsoftware.qdrant.jdbc.schema;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.client.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Points.ScrollPoints;

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
            .collection("products", FakeQdrantServer.unnamedVector(4, Distance.Cosine,
                Map.of("name", PayloadSchemaType.Keyword)), List.of(
                FakeQdrantServer.point(id(1), Map.of("name", value("Lamp"), "price", value(19.5))),
                FakeQdrantServer.point(id(2), Map.of("name", value("Desk"), "stock", value(3)))));
        gateway = QdrantGateway.open("localhost", server.port(), false, null, Duration.ofSeconds(5),
            "test");
    }

    @AfterEach
    void stop() throws Exception {
        gateway.close();
        server.close();
    }

    @Test
    void readsTheCollectionAndASampleOfPoints() throws SQLException {
        TableSchema schema = new SchemaReader(gateway, 50).read("products");

        assertEquals("products", schema.name());
        assertEquals("_id name price stock _payload _vector", names(schema));

        ScrollPoints scroll = server.scrollRequests().get(0);
        assertEquals("products", scroll.getCollectionName());
        assertEquals(50, scroll.getLimit());
        assertTrue(scroll.getWithPayload().getEnable());
        assertFalse(scroll.getWithVectors().getEnable());
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
    }

    @Test
    void asksQdrantAgainEveryTime() throws SQLException {
        SchemaReader reader = new SchemaReader(gateway, 10);

        reader.read("products");
        reader.read("products");

        assertEquals(2, server.calls("Get"));
        assertEquals(2, server.calls("Scroll"));
    }

    @Test
    void reportsAMissingCollection() {
        SQLException error = assertThrows(SQLException.class,
            () -> new SchemaReader(gateway, 10).read("missing"));

        assertEquals("42S02", error.getSQLState());
    }

    private static String names(TableSchema schema) {
        return schema.columns().stream().map(ColumnDefinition::name)
            .collect(Collectors.joining(" "));
    }
}
