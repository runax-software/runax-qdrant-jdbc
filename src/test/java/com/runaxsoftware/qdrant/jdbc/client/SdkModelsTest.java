package com.runaxsoftware.qdrant.jdbc.client;

import static io.qdrant.client.ValueFactory.list;
import static io.qdrant.client.ValueFactory.nullValue;
import static io.qdrant.client.ValueFactory.value;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class SdkModelsTest {

    @Test
    void readsAnUnnamedVector() {
        CollectionInfo info = SdkModels.collectionInfo("products", collection(
                Collections.VectorsConfig.newBuilder()
                        .setParams(VectorParams.newBuilder().setSize(4).setDistance(Distance.Cosine))
                        .build(),
                Collections.SparseVectorConfig.getDefaultInstance()));

        assertEquals(List.of(new VectorField("", VectorKind.DENSE, 4, "Cosine")), info.vectors());
        assertEquals(5, info.pointsCount());
    }

    @Test
    void readsNamedMultiAndSparseVectors() {
        CollectionInfo info = SdkModels.collectionInfo("articles", collection(
                Collections.VectorsConfig.newBuilder()
                        .setParamsMap(Collections.VectorParamsMap.newBuilder()
                                .putMap("colbert", VectorParams.newBuilder().setSize(2)
                                        .setDistance(Distance.Cosine)
                                        .setMultivectorConfig(Collections.MultiVectorConfig.newBuilder()
                                                .setComparator(Collections.MultiVectorComparator.MaxSim))
                                        .build()))
                        .build(),
                Collections.SparseVectorConfig.newBuilder()
                        .putMap("keywords", Collections.SparseVectorParams.getDefaultInstance())
                        .build()));

        assertEquals(List.of(
                new VectorField("colbert", VectorKind.MULTIVECTOR, 2, "Cosine"),
                new VectorField("keywords", VectorKind.SPARSE, 0, null)), info.vectors());
    }

    @Test
    void namesPayloadIndexTypesLikeTheRestApi() {
        Collections.CollectionInfo sdk = collection(Collections.VectorsConfig.getDefaultInstance(),
                Collections.SparseVectorConfig.getDefaultInstance()).toBuilder()
                .putPayloadSchema("name", index(PayloadSchemaType.Keyword))
                .putPayloadSchema("in_stock", index(PayloadSchemaType.Bool))
                .putPayloadSchema("created_at", index(PayloadSchemaType.Datetime))
                .build();

        Map<String, String> indexes = SdkModels.collectionInfo("products", sdk).payloadIndexes();

        assertEquals("keyword", indexes.get("name"));
        assertEquals("bool", indexes.get("in_stock"));
        assertEquals("datetime", indexes.get("created_at"));
    }

    @Test
    void readsPointIdsAsUnsignedNumbersOrUuids() {
        assertEquals(7, SdkModels.pointId(PointIdFactory.id(7)).asLong());
        JsonNode largest = SdkModels.pointId(PointIdFactory.id(-1L));
        assertEquals(new BigInteger("18446744073709551615"), largest.bigIntegerValue());
        UUID uuid = UUID.fromString("5c56c793-69f3-4fbf-87e6-c4bf54c28c26");
        assertEquals(uuid.toString(), SdkModels.pointId(PointIdFactory.id(uuid)).asText());
    }

    @Test
    void turnsPointIdsBackIntoSdkIds() {
        for (PointId id : List.of(PointIdFactory.id(7), PointIdFactory.id(-1L),
                PointIdFactory.id(UUID.fromString("5c56c793-69f3-4fbf-87e6-c4bf54c28c26")))) {
            assertEquals(id, SdkModels.pointId(SdkModels.pointId(id)));
        }
    }

    @Test
    void convertsPayloadValuesToJson() {
        Map<String, Value> payload = Map.of(
                "text", value("Lamp"),
                "count", value(3),
                "price", value(19.5),
                "flag", value(true),
                "missing", nullValue(),
                "tags", list(List.of(value("a"), value(1))),
                "specs", value(Map.of("color", value("black"))));

        JsonNode json = SdkModels.payload(payload);

        assertEquals("Lamp", json.get("text").asText());
        assertTrue(json.get("count").isIntegralNumber());
        assertEquals(19.5, json.get("price").asDouble());
        assertTrue(json.get("flag").asBoolean());
        assertTrue(json.get("missing").isNull());
        assertEquals("[\"a\",1]", json.get("tags").toString());
        assertEquals("black", json.get("specs").get("color").asText());
    }

    @Test
    void readsACollectionWithoutVectorsOrIndexes() {
        CollectionInfo info = SdkModels.collectionInfo("empty",
            Collections.CollectionInfo.getDefaultInstance());

        assertEquals("empty", info.name());
        assertEquals(0, info.pointsCount());
        assertTrue(info.vectors().isEmpty());
        assertTrue(info.payloadIndexes().isEmpty());
    }

    @Test
    void namesUnknownIndexTypesAsUnrecognized() {
        Collections.CollectionInfo sdk = collection(Collections.VectorsConfig.getDefaultInstance(),
            Collections.SparseVectorConfig.getDefaultInstance()).toBuilder()
            .putPayloadSchema("future", Collections.PayloadSchemaInfo.newBuilder()
                .setDataTypeValue(99).build())
            .build();

        assertEquals("unrecognized",
            SdkModels.collectionInfo("x", sdk).payloadIndexes().get("future"));
    }

    @Test
    void keepsEveryNamedVectorAndSparseVector() {
        CollectionInfo info = SdkModels.collectionInfo("x", collection(
            Collections.VectorsConfig.newBuilder()
                .setParamsMap(Collections.VectorParamsMap.newBuilder()
                    .putMap("text", VectorParams.newBuilder().setSize(384)
                        .setDistance(Distance.Dot).build())
                    .putMap("image", VectorParams.newBuilder().setSize(512)
                        .setDistance(Distance.Manhattan).build()))
                .build(),
            Collections.SparseVectorConfig.newBuilder()
                .putMap("bm25", Collections.SparseVectorParams.getDefaultInstance())
                .putMap("splade", Collections.SparseVectorParams.getDefaultInstance())
                .build()));

        assertEquals(4, info.vectors().size());
        assertTrue(info.vectors().contains(new VectorField("text", VectorKind.DENSE, 384, "Dot")));
        assertTrue(info.vectors().contains(
            new VectorField("image", VectorKind.DENSE, 512, "Manhattan")));
        assertEquals(VectorKind.SPARSE, info.vectors().get(2).kind());
        assertEquals(VectorKind.SPARSE, info.vectors().get(3).kind());
    }

    @Test
    void convertsNestedPayloadValues() {
        Value nested = value(Map.of("tags", list(List.of(value("a"),
            value(Map.of("deep", value(true)))))));

        JsonNode json = SdkModels.json(nested);

        assertEquals("{\"tags\":[\"a\",{\"deep\":true}]}", json.toString());
    }

    @Test
    void readsAnEmptyValueAsNull() {
        assertTrue(SdkModels.json(Value.getDefaultInstance()).isNull());
    }

    @Test
    void readsPointsWithoutVectors() {
        Point point = SdkModels.point(io.qdrant.client.grpc.Points.RetrievedPoint.newBuilder()
            .setId(PointIdFactory.id(9))
            .putPayload("kind", value("signup"))
            .build());

        assertEquals(9, point.id().asLong());
        assertEquals("signup", point.payload().get("kind").asText());
        assertEquals(null, point.vector());
    }

    private static Collections.CollectionInfo collection(Collections.VectorsConfig vectors,
            Collections.SparseVectorConfig sparse) {
        return Collections.CollectionInfo.newBuilder()
                .setPointsCount(5)
                .setConfig(Collections.CollectionConfig.newBuilder()
                        .setParams(Collections.CollectionParams.newBuilder()
                                .setVectorsConfig(vectors)
                                .setSparseVectorsConfig(sparse)))
                .build();
    }

    private static Collections.PayloadSchemaInfo index(PayloadSchemaType type) {
        return Collections.PayloadSchemaInfo.newBuilder().setDataType(type).build();
    }
}
