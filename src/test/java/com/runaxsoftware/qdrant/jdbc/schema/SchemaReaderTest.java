package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
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

    private static final Duration AN_HOUR = Duration.ofHours(1);

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

    // Reading

    @Test
    void readsTheCollectionAndASampleOfPoints() throws SQLException {
        TableSchema schema = reader(50).read("products");

        assertEquals("products", schema.name());
        assertEquals("_id name price stock _payload _vector", names(schema));
        assertEquals(42, schema.collection().pointsCount());
        assertEquals(PayloadIndexType.KEYWORD, schema.collection().payloadIndexes().get("name"));
        assertEquals("Payload field (keyword index)",
            schema.column("name").orElseThrow().remarks());
    }

    @Test
    void asksForPayloadsButNotVectors() throws SQLException {
        reader(50).read("products");

        assertEquals(Json.object("limit", 50, "with_payload", true, "with_vector", false),
            server.scrollRequests().get(0));
        assertEquals("/collections/products/points/scroll", server.requests().get(1).path());
    }

    @Test
    void readsOnlyOnePage() throws SQLException {
        TableSchema schema = reader(1).read("products");

        assertEquals("_id name price _payload _vector", names(schema));
        assertEquals(1, server.scrollRequests().size());
    }

    @Test
    void usesPayloadIndexesAloneWithoutASample() throws SQLException {
        TableSchema schema = reader(0).read("products");

        assertEquals("_id name _payload _vector", names(schema));
        assertTrue(server.scrollRequests().isEmpty());
        assertEquals(0, server.calls(Endpoint.SCROLL));
    }

    @Test
    void readsTheCollectionAnAliasPointsAt() throws SQLException {
        server.alias("current", "products");

        TableSchema schema = reader(50).read("current");

        assertEquals("current", schema.name(), "SQL that names the alias scrolls the alias");
        assertEquals("_id name price stock _payload _vector", names(schema));
        assertEquals("/collections/current/points/scroll", server.requests().get(1).path());
    }

    @Test
    void keepsAnAliasApartFromItsCollection() throws SQLException {
        SchemaReader reader = reader(10);
        server.alias("current", "products");
        reader.read("current");
        reader.read("products");

        reader.forget("products"::equals);
        reader.read("current");

        assertEquals(2, server.calls(Endpoint.COLLECTION));
    }

    @Test
    void reportsAMissingCollection() {
        SQLException error = assertThrows(SQLException.class, () -> reader(10).read("missing"));

        assertEquals("42S02", error.getSQLState());
        assertEquals(0, server.calls(Endpoint.SCROLL));
    }

    @Test
    void reportsACollectionDeletedBetweenTheTwoCalls() {
        server.fail(Endpoint.SCROLL, 404, "Not found: Collection `products` doesn't exist!");

        SQLException error = assertThrows(SQLException.class, () -> reader(10).read("products"));

        assertEquals("42S02", error.getSQLState());
    }

    // Keeping schemas

    @Test
    void keepsTheSchemaItRead() throws SQLException {
        SchemaReader reader = reader(10);

        TableSchema first = reader.read("products");

        assertSame(first, reader.read("products"));
        assertEquals(1, server.calls(Endpoint.COLLECTION));
        assertEquals(1, server.calls(Endpoint.SCROLL));
    }

    @Test
    void asksQdrantAgainForAForgottenCollection() throws SQLException {
        SchemaReader reader = reader(10);
        TableSchema first = reader.read("products");

        reader.forget("other"::equals);
        assertSame(first, reader.read("products"));

        reader.forget("products"::equals);
        assertNotSame(first, reader.read("products"));
        assertEquals(2, server.calls(Endpoint.COLLECTION));
    }

    @Test
    void asksQdrantAgainEveryTimeWithoutACache() throws SQLException {
        SchemaReader reader = new SchemaReader(gateway, 10, Duration.ZERO);

        reader.read("products");
        reader.read("products");

        assertEquals(2, server.calls(Endpoint.COLLECTION));
        assertEquals(2, server.calls(Endpoint.SCROLL));
    }

    @Test
    void keepsNothingForAMissingCollection() throws SQLException {
        SchemaReader reader = reader(10);
        assertThrows(SQLException.class, () -> reader.read("later"));

        server.collection("later", FakeQdrantServer.unnamedVector(2, "Dot", Map.of()), List.of());

        assertEquals("later", reader.read("later").name());
        assertEquals(2, server.calls(Endpoint.COLLECTION));
    }

    @Test
    void keepsNothingWhenAReadFails() throws SQLException {
        SchemaReader reader = reader(10);
        server.fail(Endpoint.SCROLL, 500, "disk full");
        assertThrows(SQLException.class, () -> reader.read("products"));

        server.recover(Endpoint.SCROLL);

        assertEquals("products", reader.read("products").name());
        assertEquals(2, server.calls(Endpoint.SCROLL));
    }

    // Keeping the collection and alias lists

    @Test
    void keepsTheListsItRead() throws SQLException {
        SchemaReader reader = reader(10);
        server.alias("current", "products");

        assertEquals(List.of("products"), reader.collections());
        assertEquals(List.of("products"), reader.collections());
        assertEquals(List.of(new QdrantAlias("current", "products")), reader.aliases());
        assertEquals(List.of(new QdrantAlias("current", "products")), reader.aliases());

        assertEquals(1, server.calls(Endpoint.COLLECTIONS));
        assertEquals(1, server.calls(Endpoint.ALIASES));
    }

    @Test
    void keepsWhatItListsAfresh() throws SQLException {
        SchemaReader reader = reader(10);
        reader.collections();
        reader.aliases();
        server.collection("later", FakeQdrantServer.unnamedVector(2, "Dot", Map.of()), List.of())
            .alias("current", "later");

        assertEquals(List.of("products"), reader.collections(), "kept");
        assertEquals(List.of("products", "later"), reader.listCollections());
        assertEquals(List.of("products", "later"), reader.collections());
        assertEquals(List.of(new QdrantAlias("current", "later")), reader.listAliases());
        assertEquals(List.of(new QdrantAlias("current", "later")), reader.aliases());

        assertEquals(2, server.calls(Endpoint.COLLECTIONS));
        assertEquals(2, server.calls(Endpoint.ALIASES));
    }

    @Test
    void keepsTheListsApartFromTheSchemas() throws SQLException {
        SchemaReader reader = reader(10);
        reader.collections();

        reader.forget(name -> true);
        reader.collections();

        assertEquals(1, server.calls(Endpoint.COLLECTIONS));
    }

    @Test
    void listsEveryTimeWithoutACache() throws SQLException {
        SchemaReader reader = new SchemaReader(gateway, 10, Duration.ZERO);

        reader.collections();
        reader.collections();
        reader.aliases();
        reader.aliases();

        assertEquals(2, server.calls(Endpoint.COLLECTIONS));
        assertEquals(2, server.calls(Endpoint.ALIASES));
    }

    @Test
    void findsTheCollectionOfANameInTheKeptLists() throws SQLException {
        SchemaReader reader = reader(10);
        server.alias("current", "products");

        assertEquals("products", reader.collectionOf("products"));
        assertEquals(0, server.calls(Endpoint.ALIASES), "a collection needs no alias list");
        assertEquals("products", reader.collectionOf("current"));
        assertEquals("products", reader.collectionOf("current"));

        assertEquals(1, server.calls(Endpoint.COLLECTIONS));
        assertEquals(1, server.calls(Endpoint.ALIASES));
    }

    @Test
    void listsAgainOnceForANameTheKeptListsLack() throws SQLException {
        SchemaReader reader = reader(10);
        reader.collections();
        reader.aliases();
        server.collection("later", FakeQdrantServer.unnamedVector(2, "Dot", Map.of()), List.of())
            .alias("current", "later");

        assertEquals("later", reader.collectionOf("later"));
        assertEquals("later", reader.collectionOf("current"), "kept from the same listing");
        assertEquals(2, server.calls(Endpoint.COLLECTIONS));
        assertEquals(2, server.calls(Endpoint.ALIASES));

        assertNull(reader.collectionOf("nothing"));
        assertEquals(3, server.calls(Endpoint.COLLECTIONS));
        assertEquals(3, server.calls(Endpoint.ALIASES));
    }

    @Test
    void namesNothingForNull() throws SQLException {
        assertNull(reader(10).collectionOf(null));
        assertEquals(0, server.requests().size());
    }

    @Test
    void listsOnlyOnceForAMissingNameWithoutACache() throws SQLException {
        SchemaReader reader = new SchemaReader(gateway, 10, Duration.ZERO);

        assertNull(reader.collectionOf("nothing"));

        assertEquals(1, server.calls(Endpoint.COLLECTIONS));
        assertEquals(1, server.calls(Endpoint.ALIASES));
    }

    @Test
    void returnsListsThatCannotChange() throws SQLException {
        List<String> collections = reader(10).collections();

        assertThrows(UnsupportedOperationException.class, () -> collections.add("x"));
    }

    private SchemaReader reader(int sampleSize) {
        return new SchemaReader(gateway, sampleSize, AN_HOUR);
    }

    private static String names(TableSchema schema) {
        return schema.columns().stream().map(ColumnDefinition::name)
            .collect(Collectors.joining(" "));
    }
}
