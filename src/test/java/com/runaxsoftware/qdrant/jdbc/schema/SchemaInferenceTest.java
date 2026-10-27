package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class SchemaInferenceTest {

    private static final VectorField UNNAMED = new VectorField("", VectorKind.DENSE, 4, "Cosine");

    @Test
    void ordersColumnsAsIdFieldsPayloadThenVectors() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(),
            "{\"b\": 1, \"a\": \"x\"}");

        assertEquals("_id a b _payload _vector", names(schema));
    }

    @Test
    void describesTheDriversOwnColumns() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"a\": 1}");

        ColumnDefinition id = column(schema, "_id");
        assertEquals(ColumnKind.ID, id.kind());
        assertEquals(ColumnType.TEXT, id.type());
        assertEquals(DriverColumns.ID.remarks(), id.remarks());

        ColumnDefinition payload = column(schema, "_payload");
        assertEquals(ColumnKind.PAYLOAD, payload.kind());
        assertEquals(ColumnType.JSON, payload.type());
        assertEquals(DriverColumns.PAYLOAD.remarks(), payload.remarks());
    }

    @Test
    void typesIndexedFieldsFromTheirIndex() {
        Map<String, String> indexes = new LinkedHashMap<>();
        indexes.put("name", "keyword");
        indexes.put("price", "float");
        indexes.put("year", "integer");
        indexes.put("created_at", "datetime");
        indexes.put("in_stock", "bool");
        indexes.put("id_key", "uuid");
        indexes.put("location", "geo");

        TableSchema schema = infer(List.of(UNNAMED), indexes,
            "{\"name\": \"Lamp\", \"price\": 19, \"year\": 2024,"
                + " \"created_at\": \"2024-03-01T10:00:00Z\", \"in_stock\": true,"
                + " \"id_key\": \"5c56c793-69f3-4fbf-87e6-c4bf54c28c26\","
                + " \"location\": {\"lon\": 1.0, \"lat\": 2.0}}",
            "{\"name\": \"Chair\", \"price\": 189.5}");

        assertEquals(ColumnType.TEXT, type(schema, "name"));
        assertEquals(ColumnType.FLOAT, type(schema, "price"));
        assertEquals(ColumnType.INTEGER, type(schema, "year"));
        assertEquals(ColumnType.DATETIME, type(schema, "created_at"));
        assertEquals(ColumnType.BOOLEAN, type(schema, "in_stock"));
        assertEquals(ColumnType.TEXT, type(schema, "id_key"));
        assertEquals(ColumnType.JSON, type(schema, "location"));
        assertEquals("Payload field (keyword index)", column(schema, "name").remarks());
        assertEquals("Payload field (geo index)", column(schema, "location").remarks());
    }

    @Test
    void typesUnindexedFieldsFromTheSample() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(),
            "{\"kind\": \"signup\", \"count\": 3, \"ratio\": 0.5, \"flag\": true,"
                + " \"done\": false, \"meta\": {\"a\": 1}, \"tags\": [\"x\"]}",
            "{\"count\": 2.5, \"flag\": \"yes\", \"done\": true}");

        assertEquals(ColumnType.TEXT, type(schema, "kind"));
        assertEquals(ColumnType.FLOAT, type(schema, "count"));
        assertEquals(ColumnType.FLOAT, type(schema, "ratio"));
        assertEquals(ColumnType.JSON, type(schema, "flag"));
        assertEquals(ColumnType.BOOLEAN, type(schema, "done"));
        assertEquals(ColumnType.JSON, type(schema, "meta"));
        assertEquals(ColumnType.JSON, type(schema, "tags"));
    }

    @Test
    void typesWholeNumbersAsIntegers() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"count\": 3}",
            "{\"count\": 9007199254740993}");

        assertEquals(ColumnType.INTEGER, type(schema, "count"));
    }

    @Test
    void saysWhichFieldsAreNotIndexed() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"kind\": \"signup\"}");

        assertEquals("Payload field (not indexed)", column(schema, "kind").remarks());
    }

    @Test
    void addsIndexedNestedFieldsAsTheirOwnColumns() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("specs.color", "keyword"),
            "{\"specs\": {\"color\": \"black\", \"weight\": 1.2}}");

        assertEquals("_id specs specs.color _payload _vector", names(schema));
        assertEquals(ColumnType.JSON, type(schema, "specs"));
        assertEquals(ColumnType.TEXT, type(schema, "specs.color"));
        assertEquals("specs.color", column(schema, "specs.color").source());
        assertEquals(ColumnKind.PAYLOAD_FIELD, column(schema, "specs.color").kind());
        assertEquals("Nested payload field (keyword index)",
            column(schema, "specs.color").remarks());
    }

    @Test
    void keepsIndexedFieldsWhenTheSampleIsEmpty() {
        TableSchema schema = SchemaInference.infer(
            new QdrantCollection("empty", 0, List.of(UNNAMED),
                Map.of("lang", PayloadIndexType.KEYWORD)),
            List.of());

        assertEquals("_id lang _payload _vector", names(schema));
        assertEquals(ColumnType.TEXT, type(schema, "lang"));
    }

    @Test
    void givesPayloadFieldsNamedIdPayloadOrVectorTheirOwnColumns() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(),
            "{\"id\": \"evt-1\", \"payload\": 1, \"vector\": [1], \"kind\": \"a\"}");

        assertEquals("_id id kind payload vector _payload _vector", names(schema));
        assertEquals(ColumnKind.ID, column(schema, "_id").kind());
        assertEquals(ColumnKind.PAYLOAD_FIELD, column(schema, "id").kind());
        assertEquals("id", column(schema, "id").source());
    }

    @Test
    void leavesPayloadFieldsNamedLikeReservedColumnsInThePayload() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("_score", "float", "_id", "keyword"),
            "{\"_id\": \"mongo-id\", \"_payload\": 1, \"_vector\": 2, \"_score\": 0.5,"
                + " \"_group\": 3, \"_lookup\": 4, \"kind\": \"a\"}");

        assertEquals("_id kind _payload _vector", names(schema));
        assertEquals(ColumnKind.ID, column(schema, "_id").kind());
    }

    @Test
    void keepsOtherUnderscoreFieldsAsColumns() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"_ts\": 1, \"id\": \"x\"}");

        assertEquals("_id _ts id _payload _vector", names(schema));
    }

    @Test
    void letsAVectorWinOverAPayloadFieldOfTheSameName() {
        TableSchema schema = infer(List.of(new VectorField("dense", VectorKind.DENSE, 4, "Dot")),
            Map.of(), "{\"dense\": \"text\", \"kind\": \"a\"}");

        assertEquals("_id kind _payload dense", names(schema));
        assertEquals(ColumnKind.VECTOR, column(schema, "dense").kind());
    }

    @Test
    void letsARenamedVectorWinOverAPayloadFieldOfTheSameName() {
        TableSchema schema = infer(List.of(new VectorField("_score", VectorKind.DENSE, 3, "Dot")),
            Map.of(), "{\"_score_vector\": 1, \"kind\": \"a\"}");

        assertEquals("_id kind _payload _score_vector", names(schema));
        assertEquals(ColumnKind.VECTOR, column(schema, "_score_vector").kind());
    }

    @Test
    void namesAndOrdersVectorColumns() {
        TableSchema schema = infer(List.of(
            new VectorField("keywords", VectorKind.SPARSE, 0, null),
            new VectorField("dense", VectorKind.DENSE, 4, "Dot"),
            new VectorField("colbert", VectorKind.MULTIVECTOR, 2, "Cosine"),
            new VectorField("payload", VectorKind.DENSE, 3, "Cosine"),
            new VectorField("_score", VectorKind.DENSE, 3, "Cosine")), Map.of());

        assertEquals("_id _payload _score_vector colbert dense payload keywords", names(schema));
        assertEquals(ColumnType.MULTIVECTOR, type(schema, "colbert"));
        assertEquals(ColumnType.VECTOR, type(schema, "dense"));
        assertEquals(ColumnType.SPARSE_VECTOR, type(schema, "keywords"));
        assertEquals(4, column(schema, "dense").size());
        assertEquals("payload", column(schema, "payload").source());
        assertEquals("_score", column(schema, "_score_vector").source());
        assertEquals("Dense vector, 4 dimensions, Dot distance", column(schema, "dense").remarks());
        assertEquals("Multivector of 2-dimension vectors, Cosine distance",
            column(schema, "colbert").remarks());
        assertEquals("Sparse vector", column(schema, "keywords").remarks());
    }

    @Test
    void makesOnlyTheIdNotNull() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"a\": 1}");

        assertFalse(column(schema, "_id").nullable());
        assertTrue(column(schema, "a").nullable());
        assertTrue(column(schema, "_payload").nullable());
        assertTrue(column(schema, "_vector").nullable());
    }

    @Test
    void ignoresPointsWithoutPayload() {
        TableSchema schema = SchemaInference.infer(
            new QdrantCollection("test", 1, List.of(UNNAMED), Map.of()),
            List.of(new Point(Json.node(1), null, null)));

        assertEquals("_id _payload _vector", names(schema));
    }

    @Test
    void addsIndexedArrayPathsWithoutSamplingThem() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("variants[].sku", "keyword"),
            "{\"variants\": [{\"sku\": \"A1\"}]}");

        assertEquals("_id variants variants[].sku _payload _vector", names(schema));
        assertEquals(ColumnType.JSON, type(schema, "variants"));
        assertEquals(ColumnType.TEXT, type(schema, "variants[].sku"));
        assertEquals("Nested payload field (keyword index)",
            column(schema, "variants[].sku").remarks());
    }

    @Test
    void typesNestedIndexedFieldsFromTheirSampledValues() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("specs.weight", "integer"),
            "{\"specs\": {\"weight\": 1.5}}", "{\"specs\": {}}", "{\"other\": 1}");

        assertEquals(ColumnType.FLOAT, type(schema, "specs.weight"));
    }

    @Test
    void readsNestedIndexedFieldsFromPointsWithoutPayload() {
        TableSchema schema = SchemaInference.infer(
            new QdrantCollection("test", 1, List.of(UNNAMED),
                Map.of("specs.weight", PayloadIndexType.INTEGER)),
            List.of(new Point(Json.node(1), null, null)));

        assertEquals("_id specs.weight _payload _vector", names(schema));
        assertEquals(ColumnType.INTEGER, type(schema, "specs.weight"));
    }

    @Test
    void fallsBackToJsonWhenIndexAndValuesDisagree() {
        TableSchema schema = infer(List.of(UNNAMED),
            Map.of("created_at", "datetime", "title", "text", "flag", "bool"),
            "{\"created_at\": 1700000000, \"title\": \"Hello\", \"flag\": \"yes\"}");

        assertEquals(ColumnType.JSON, type(schema, "created_at"));
        assertEquals(ColumnType.TEXT, type(schema, "title"));
        assertEquals(ColumnType.JSON, type(schema, "flag"));
    }

    @Test
    void usesSampledValuesForUnknownIndexTypes() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("rank", "hyperdimensional"),
            "{\"rank\": 3}");

        assertEquals(ColumnType.INTEGER, type(schema, "rank"));
        assertEquals("Payload field (unknown index)", column(schema, "rank").remarks());
    }

    @Test
    void treatsFieldsThatAreAlwaysNullAsText() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"note\": null}");

        assertEquals(ColumnType.TEXT, type(schema, "note"));
    }

    @Test
    void readsWholeNumbersWrittenWithADecimalPointAsFloats() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"score\": 1.0}");

        assertEquals(ColumnType.FLOAT, type(schema, "score"));
    }

    @Test
    void sortsPayloadFieldsByName() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("b.c", "keyword"),
            "{\"c\": 1, \"B\": 2, \"a\": 3, \"b\": {\"c\": \"x\"}}");

        assertEquals("_id B a b b.c c _payload _vector", names(schema));
    }

    @Test
    void namesTheUnnamedVectorAfterItsKind() {
        TableSchema schema = infer(List.of(
            new VectorField("", VectorKind.MULTIVECTOR, 128, "Cosine")), Map.of());

        assertEquals(ColumnType.MULTIVECTOR, type(schema, "_vector"));
        assertEquals("", column(schema, "_vector").source());
        assertEquals(128, column(schema, "_vector").size());
    }

    @Test
    void makesATableWithoutVectors() {
        TableSchema schema = infer(List.of(), Map.of(), "{\"a\": 1}");

        assertEquals("_id a _payload", names(schema));
    }

    @Test
    void keepsTheCollectionInTheSchema() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of());

        assertEquals("test", schema.name());
        assertEquals(List.of(UNNAMED), schema.collection().vectors());
    }

    @Test
    void returnsColumnsThatCannotChange() {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"a\": 1}");

        assertThrows(UnsupportedOperationException.class, () -> schema.columns().clear());
    }

    private static TableSchema infer(List<VectorField> vectors, Map<String, String> indexes,
                                     String... payloads) {
        List<Point> sample = new ArrayList<>();

        for (int i = 0; i < payloads.length; i++) {
            sample.add(new Point(Json.node(i + 1), Json.parse(payloads[i]), null));
        }

        Map<String, PayloadIndexType> indexTypes = new LinkedHashMap<>();
        indexes.forEach((path, type) -> indexTypes.put(path, PayloadIndexType.fromQdrant(type)));

        return SchemaInference.infer(
            new QdrantCollection("test", sample.size(), vectors, indexTypes), sample);
    }

    private static String names(TableSchema schema) {
        return schema.columns().stream().map(ColumnDefinition::name)
            .collect(Collectors.joining(" "));
    }

    private static ColumnDefinition column(TableSchema schema, String name) {
        return schema.column(name).orElseThrow(() -> new AssertionError("No column " + name));
    }

    private static ColumnType type(TableSchema schema, String name) {
        return column(schema, name).type();
    }
}
