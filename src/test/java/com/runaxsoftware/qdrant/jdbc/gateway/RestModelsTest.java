package com.runaxsoftware.qdrant.jdbc.gateway;

import static com.runaxsoftware.qdrant.jdbc.support.Json.array;
import static com.runaxsoftware.qdrant.jdbc.support.Json.object;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollPage;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

class RestModelsTest {

    @Test
    void readsAnUnnamedVector() {
        QdrantCollection collection = RestModels.collection("products", info(
            object("size", 4, "distance", "Cosine"), null, object()));

        assertEquals("products", collection.name());
        assertEquals(5, collection.pointsCount());
        assertEquals(List.of(new VectorField("", VectorKind.DENSE, 4, "Cosine")),
            collection.vectors());
    }

    @Test
    void readsAnUnnamedMultivector() {
        QdrantCollection collection = RestModels.collection("x", info(
            object("size", 128, "distance", "Cosine",
                "multivector_config", object("comparator", "max_sim")), null, object()));

        assertEquals(List.of(new VectorField("", VectorKind.MULTIVECTOR, 128, "Cosine")),
            collection.vectors());
    }

    @Test
    void readsNamedMultiAndSparseVectors() {
        QdrantCollection collection = RestModels.collection("articles", info(
            object("colbert", object("size", 2, "distance", "Cosine",
                "multivector_config", object("comparator", "max_sim"))),
            object("keywords", object()), object()));

        assertEquals(List.of(
            new VectorField("colbert", VectorKind.MULTIVECTOR, 2, "Cosine"),
            new VectorField("keywords", VectorKind.SPARSE, 0, null)), collection.vectors());
    }

    @Test
    void keepsNamedVectorsInQdrantsOrderBeforeSparseOnes() {
        QdrantCollection collection = RestModels.collection("x", info(
            object("text", object("size", 384, "distance", "Dot"),
                "image", object("size", 512, "distance", "Manhattan")),
            object("splade", object(), "bm25", object("modifier", "idf")), object()));

        assertEquals(List.of(
            new VectorField("text", VectorKind.DENSE, 384, "Dot"),
            new VectorField("image", VectorKind.DENSE, 512, "Manhattan"),
            new VectorField("splade", VectorKind.SPARSE, 0, null),
            new VectorField("bm25", VectorKind.SPARSE, 0, null)), collection.vectors());
    }

    @Test
    void readsPayloadIndexTypes() {
        QdrantCollection collection = RestModels.collection("products", info(
            object("size", 4, "distance", "Cosine"), null, object(
                "name", object("data_type", "keyword", "points", 5),
                "in_stock", object("data_type", "bool", "points", 5),
                "created_at", object("data_type", "datetime", "points", 5),
                "future", object("data_type", "hyperdimensional"),
                "broken", object())));

        Map<String, PayloadIndexType> indexes = collection.payloadIndexes();

        assertEquals(List.of("name", "in_stock", "created_at", "future", "broken"),
            List.copyOf(indexes.keySet()));
        assertEquals(PayloadIndexType.KEYWORD, indexes.get("name"));
        assertEquals(PayloadIndexType.BOOL, indexes.get("in_stock"));
        assertEquals(PayloadIndexType.DATETIME, indexes.get("created_at"));
        assertEquals(PayloadIndexType.UNKNOWN, indexes.get("future"));
        assertEquals(PayloadIndexType.UNKNOWN, indexes.get("broken"));
    }

    @Test
    void readsACollectionWithoutVectorsIndexesOrACount() {
        QdrantCollection collection = RestModels.collection("empty", object(
            "points_count", null, "config", object("params", object())));

        assertEquals("empty", collection.name());
        assertEquals(0, collection.pointsCount());
        assertTrue(collection.vectors().isEmpty());
        assertTrue(collection.payloadIndexes().isEmpty());
    }

    @Test
    void returnsCollectionsThatCannotChange() {
        QdrantCollection collection = RestModels.collection("products", info(
            object("size", 4, "distance", "Cosine"), null,
            object("name", object("data_type", "keyword"))));

        assertThrows(UnsupportedOperationException.class, () -> collection.vectors().clear());
        assertThrows(UnsupportedOperationException.class,
            () -> collection.payloadIndexes().clear());
    }

    @Test
    void readsAScrollPage() {
        ScrollPage page = RestModels.scrollPage(object(
            "points", array(
                object("id", 1, "payload", object("kind", "signup"), "vector", array(0.1, 0.2)),
                object("id", "5c56c793-69f3-4fbf-87e6-c4bf54c28c26", "payload", object())),
            "next_page_offset", 3));

        List<Point> points = page.points();
        assertEquals(2, points.size());
        assertEquals(1, points.get(0).id().asLong());
        assertEquals("signup", points.get(0).payload().get("kind").asText());
        assertEquals(array(0.1, 0.2), points.get(0).vector());
        assertEquals("5c56c793-69f3-4fbf-87e6-c4bf54c28c26", points.get(1).id().asText());
        assertTrue(points.get(1).payload().isEmpty());
        assertNull(points.get(1).vector());
        assertEquals(3, page.nextPageOffset().asInt());
    }

    @Test
    void readsMissingOrNullPayloadsAndVectorsAsNull() {
        ScrollPage page = RestModels.scrollPage(object("points", array(
            object("id", 1),
            object("id", 2, "payload", null, "vector", null))));

        for (Point point : page.points()) {
            assertNull(point.payload());
            assertNull(point.vector());
        }
    }

    @Test
    void keepsNamedVectorsAsAnObject() {
        ScrollPage page = RestModels.scrollPage(object("points", array(object("id", 1,
            "vector", object("dense", array(0.1), "keywords",
                object("indices", array(1), "values", array(0.5)))))));

        assertEquals(array(0.1), page.points().get(0).vector().get("dense"));
        assertTrue(page.points().get(0).vector().get("keywords").isObject());
    }

    @Test
    void endsOnTheLastPage() {
        assertNull(RestModels.scrollPage(object("points", array(),
            "next_page_offset", null)).nextPageOffset());
        assertNull(RestModels.scrollPage(object("points", array())).nextPageOffset());
    }

    @Test
    void readsAnEmptyPage() {
        assertTrue(RestModels.scrollPage(object()).points().isEmpty());
    }

    @Test
    void keepsTheLargestUnsignedIdExact() {
        BigInteger largest = new BigInteger("18446744073709551615");

        ScrollPage page = RestModels.scrollPage(Json.parse(
            "{\"points\": [{\"id\": 18446744073709551615}],"
                + " \"next_page_offset\": 18446744073709551615}"));

        assertEquals(largest, page.points().get(0).id().bigIntegerValue());
        assertEquals(largest, page.nextPageOffset().bigIntegerValue());
    }

    @Test
    void returnsPagesThatCannotChange() {
        ScrollPage page = RestModels.scrollPage(object("points", array(object("id", 1))));

        assertThrows(UnsupportedOperationException.class, () -> page.points().clear());
    }

    /**
     * A collection as {@code GET /collections/{name}} returns it in {@code result}, with 5 points.
     */
    private static ObjectNode info(ObjectNode vectors, ObjectNode sparseVectors,
                                   ObjectNode payloadSchema) {
        ObjectNode params = object("vectors", vectors, "shard_number", 1);

        if (sparseVectors != null) {
            params.set("sparse_vectors", sparseVectors);
        }

        return object("status", "green", "points_count", 5,
            "config", object("params", params), "payload_schema", payloadSchema);
    }
}
