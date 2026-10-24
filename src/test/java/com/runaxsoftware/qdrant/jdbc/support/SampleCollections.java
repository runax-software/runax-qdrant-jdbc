package com.runaxsoftware.qdrant.jdbc.support;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.list;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorFactory.multiVector;
import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;
import static io.qdrant.client.VectorsFactory.vectors;

import com.runaxsoftware.qdrant.jdbc.client.QdrantGateway;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.MultiVectorComparator;
import io.qdrant.client.grpc.Collections.MultiVectorConfig;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.Vectors;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Three small collections that cover the cases the driver has to handle: an unnamed vector,
 * named dense, multi-, and sparse vectors, numeric and UUID point IDs (including the largest
 * unsigned one), indexed and unindexed payload fields, nested objects, arrays, fields with
 * mixed value types, and a payload field named like a reserved column.
 */
public final class SampleCollections {

    /** The collection names, without a prefix. */
    public static final List<String> NAMES = List.of("articles", "events", "products");

    private SampleCollections() {
    }

    /** Creates the collections, replacing any with the same names. */
    public static void create(QdrantGateway gateway, String prefix) throws SQLException {
        delete(gateway, prefix);
        createProducts(gateway, prefix + "products");
        createArticles(gateway, prefix + "articles");
        createEvents(gateway, prefix + "events");
    }

    /** Deletes the collections that exist. */
    public static void delete(QdrantGateway gateway, String prefix) throws SQLException {
        QdrantClient sdk = gateway.sdk();
        for (String name : NAMES) {
            if (gateway.await(sdk.collectionExistsAsync(prefix + name))) {
                gateway.await(sdk.deleteCollectionAsync(prefix + name));
            }
        }
    }

    /** An unnamed vector, typed payload indexes, a nested indexed field, and arrays. */
    private static void createProducts(QdrantGateway gateway, String name) throws SQLException {
        createCollection(gateway, CreateCollection.newBuilder()
                .setCollectionName(name)
                .setVectorsConfig(VectorsConfig.newBuilder().setParams(dense(4, Distance.Cosine)))
                .build());
        index(gateway, name, "name", PayloadSchemaType.Keyword);
        index(gateway, name, "price", PayloadSchemaType.Float);
        index(gateway, name, "created_at", PayloadSchemaType.Datetime);
        index(gateway, name, "specs.color", PayloadSchemaType.Keyword);
        index(gateway, name, "tags", PayloadSchemaType.Keyword);

        upsert(gateway, name, List.of(
                point(id(1), vectors(0.9f, 0.1f, 0.1f, 0.2f), payload(
                        "name", value("Desk lamp"), "price", value(39.9), "in_stock", value(true),
                        "tags", list(List.of(value("lighting"), value("office"))),
                        "created_at", value("2024-03-01T10:00:00Z"),
                        "specs", value(fields("color", value("black"), "weight", value(1.2))))),
                point(id(2), vectors(0.2f, 0.8f, 0.3f, 0.1f), payload(
                        "name", value("Office chair"), "price", value(189.0), "in_stock", value(true),
                        "tags", list(List.of(value("furniture"), value("office"))),
                        "created_at", value("2024-04-12T08:30:00Z"),
                        "specs", value(fields("color", value("grey"), "weight", value(12.5))))),
                point(id(3), vectors(0.1f, 0.7f, 0.6f, 0.2f), payload(
                        "name", value("Standing desk"), "price", value(449.0),
                        "in_stock", value(false), "tags", list(List.of(value("furniture"))),
                        "created_at", value("2024-06-20T14:15:00Z"),
                        "specs", value(fields("color", value("white"), "weight", value(30))))),
                point(id(4), vectors(0.7f, 0.2f, 0.5f, 0.3f), payload(
                        "name", value("Monitor arm"), "price", value(89.5), "in_stock", value(true),
                        "tags", list(List.of(value("office"))),
                        "created_at", value("2024-07-02T09:00:00Z"),
                        "specs", value(fields("color", value("black"), "weight", value(2.1))))),
                point(id(5), vectors(0.3f, 0.3f, 0.2f, 0.9f), payload(
                        "name", value("Cable tray"), "price", value(19), "in_stock", value(true),
                        "tags", list(List.of()),
                        "created_at", value("2024-08-15T16:45:00Z"),
                        "specs", value(fields("color", value("white"), "weight", value(0.4)))))));
    }

    /** Named dense, multi-, and sparse vectors; UUID IDs; full-text and integer indexes. */
    private static void createArticles(QdrantGateway gateway, String name) throws SQLException {
        createCollection(gateway, CreateCollection.newBuilder()
                .setCollectionName(name)
                .setVectorsConfig(VectorsConfig.newBuilder().setParamsMap(VectorParamsMap.newBuilder()
                        .putMap("dense", dense(4, Distance.Dot))
                        .putMap("colbert", dense(2, Distance.Cosine).toBuilder()
                                .setMultivectorConfig(MultiVectorConfig.newBuilder()
                                        .setComparator(MultiVectorComparator.MaxSim))
                                .build())))
                .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
                        .putMap("keywords", SparseVectorParams.getDefaultInstance()))
                .build());
        index(gateway, name, "lang", PayloadSchemaType.Keyword);
        index(gateway, name, "year", PayloadSchemaType.Integer);
        index(gateway, name, "title", PayloadSchemaType.Text);
        index(gateway, name, "author.name", PayloadSchemaType.Keyword);

        upsert(gateway, name, List.of(
                point(id(UUID.fromString("5c56c793-69f3-4fbf-87e6-c4bf54c28c26")),
                        articleVectors(new float[] {0.1f, 0.2f, 0.3f, 0.4f}, 1, 7),
                        payload("title", value("Refund policy explained"), "lang", value("en"),
                                "year", value(2024),
                                "author", value(fields("name", value("Ann"), "country", value("UK"))))),
                point(id(UUID.fromString("0b3e9a2c-7a51-4c6e-9d0f-2f4a6c8e1b37")),
                        articleVectors(new float[] {0.4f, 0.3f, 0.2f, 0.1f}, 2, 9),
                        payload("title", value("Rückgaberecht im Überblick"), "lang", value("de"),
                                "year", value(2023),
                                "author", value(fields("name", value("Jonas"), "country", value("DE"))))),
                point(id(UUID.fromString("e7c1f0d4-3b2a-4f6e-8a9d-1c5b7e3f2a60")),
                        articleVectors(new float[] {0.2f, 0.2f, 0.5f, 0.1f}, 1, 4),
                        payload("title", value("Shipping times by region"), "lang", value("en"),
                                "year", value(2025),
                                "author", value(fields("name", value("Mei"), "country", value("SG")))))));
    }

    /**
     * No payload indexes, so every column comes from sampling: mixed number types, mixed
     * value types, an object in one point only, an empty payload, a payload field called
     * {@code id}, and numeric and UUID IDs side by side.
     */
    private static void createEvents(QdrantGateway gateway, String name) throws SQLException {
        createCollection(gateway, CreateCollection.newBuilder()
                .setCollectionName(name)
                .setVectorsConfig(VectorsConfig.newBuilder().setParams(dense(2, Distance.Euclid)))
                .build());

        upsert(gateway, name, List.of(
                point(id(1), vectors(0.1f, 0.2f), payload(
                        "kind", value("signup"), "count", value(3), "flag", value(true),
                        "id", value("evt-1"))),
                point(id(2), vectors(0.3f, 0.1f), payload(
                        "kind", value("purchase"), "count", value(2.5), "flag", value("yes"),
                        "meta", value(fields("source", value("web"), "campaign", value("spring"))))),
                point(id(-1L), vectors(0.5f, 0.5f), payload(
                        "kind", value("refund"), "count", value(1))),
                point(id(UUID.fromString("9f8e7d6c-5b4a-4321-8fed-cba987654321")),
                        vectors(0.9f, 0.4f), payload())));
    }

    private static VectorParams dense(int size, Distance distance) {
        return VectorParams.newBuilder().setSize(size).setDistance(distance).build();
    }

    private static Vectors articleVectors(float[] dense, int firstTerm, int secondTerm) {
        return namedVectors(Map.of(
                "dense", vector(dense),
                "colbert", multiVector(new float[][] {{dense[0], dense[1]}, {dense[2], dense[3]}}),
                "keywords", vector(List.of(0.5f, 0.9f), List.of(firstTerm, secondTerm))));
    }

    private static PointStruct point(PointId id, Vectors vectors, Map<String, Value> payload) {
        return PointStruct.newBuilder().setId(id).setVectors(vectors).putAllPayload(payload).build();
    }

    /** A payload from alternating names and values. */
    private static Map<String, Value> payload(Object... namesAndValues) {
        return fields(namesAndValues);
    }

    private static Map<String, Value> fields(Object... namesAndValues) {
        Map<String, Value> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.put((String) namesAndValues[i], (Value) namesAndValues[i + 1]);
        }
        return fields;
    }

    private static void createCollection(QdrantGateway gateway, CreateCollection request)
            throws SQLException {
        gateway.await(gateway.sdk().createCollectionAsync(request));
    }

    private static void index(QdrantGateway gateway, String collection, String field,
            PayloadSchemaType type) throws SQLException {
        gateway.await(gateway.sdk().createPayloadIndexAsync(collection, field, type, null, true,
                null, null));
    }

    private static void upsert(QdrantGateway gateway, String collection, List<PointStruct> points)
            throws SQLException {
        gateway.await(gateway.sdk().upsertAsync(collection, points));
    }
}
