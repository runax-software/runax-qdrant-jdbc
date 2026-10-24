package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runaxsoftware.qdrant.jdbc.client.CollectionInfo;
import com.runaxsoftware.qdrant.jdbc.client.Point;
import com.runaxsoftware.qdrant.jdbc.client.VectorField;
import com.runaxsoftware.qdrant.jdbc.client.VectorKind;
import com.runaxsoftware.qdrant.jdbc.schema.SchemaInference.ValueKind;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class SchemaInferenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final VectorField UNNAMED = new VectorField("", VectorKind.DENSE, 4, "Cosine");

    @Test
    void ordersColumnsAsIdFieldsPayloadThenVectors() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(),
                "{\"b\": 1, \"a\": \"x\"}");

        assertEquals("_id a b _payload _vector", names(schema));
    }

    @Test
    void typesIndexedFieldsFromTheirIndex() throws Exception {
        Map<String, String> indexes = new LinkedHashMap<>();
        indexes.put("name", "keyword");
        indexes.put("price", "float");
        indexes.put("year", "integer");
        indexes.put("created_at", "datetime");
        indexes.put("in_stock", "bool");

        TableSchema schema = infer(List.of(UNNAMED), indexes,
                "{\"name\": \"Lamp\", \"price\": 19, \"year\": 2024,"
                        + " \"created_at\": \"2024-03-01T10:00:00Z\", \"in_stock\": true}",
                "{\"name\": \"Chair\", \"price\": 189.5}");

        assertEquals(ColumnType.TEXT, type(schema, "name"));
        assertEquals(ColumnType.FLOAT, type(schema, "price"));
        assertEquals(ColumnType.INTEGER, type(schema, "year"));
        assertEquals(ColumnType.DATETIME, type(schema, "created_at"));
        assertEquals(ColumnType.BOOLEAN, type(schema, "in_stock"));
        assertEquals("Payload field (keyword index)", column(schema, "name").remarks());
    }

    @Test
    void typesUnindexedFieldsFromTheSample() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(),
                "{\"kind\": \"signup\", \"count\": 3, \"ratio\": 0.5, \"flag\": true,"
                        + " \"meta\": {\"a\": 1}, \"tags\": [\"x\"]}",
                "{\"count\": 2.5, \"flag\": \"yes\"}");

        assertEquals(ColumnType.TEXT, type(schema, "kind"));
        assertEquals(ColumnType.FLOAT, type(schema, "count"));
        assertEquals(ColumnType.FLOAT, type(schema, "ratio"));
        assertEquals(ColumnType.JSON, type(schema, "flag"));
        assertEquals(ColumnType.JSON, type(schema, "meta"));
        assertEquals(ColumnType.JSON, type(schema, "tags"));
        assertEquals("Payload field (not indexed)", column(schema, "kind").remarks());
    }

    @Test
    void addsIndexedNestedFieldsAsTheirOwnColumns() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("specs.color", "keyword"),
                "{\"specs\": {\"color\": \"black\", \"weight\": 1.2}}");

        assertEquals("_id specs specs.color _payload _vector", names(schema));
        assertEquals(ColumnType.JSON, type(schema, "specs"));
        assertEquals(ColumnType.TEXT, type(schema, "specs.color"));
        assertEquals("specs.color", column(schema, "specs.color").source());
        assertEquals("Nested payload field (keyword index)",
                column(schema, "specs.color").remarks());
    }

    @Test
    void keepsIndexedFieldsWhenTheSampleIsEmpty() {
        TableSchema schema = SchemaInference.infer(
                new CollectionInfo("empty", 0, List.of(UNNAMED), Map.of("lang", "keyword")),
                List.of());

        assertEquals("_id lang _payload _vector", names(schema));
    }

    @Test
    void givesPayloadFieldsNamedIdPayloadOrVectorTheirOwnColumns() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(),
            "{\"id\": \"evt-1\", \"payload\": 1, \"vector\": [1], \"kind\": \"a\"}");

        assertEquals("_id id kind payload vector _payload _vector", names(schema));
        assertEquals(ColumnKind.ID, column(schema, "_id").kind());
        assertEquals(ColumnKind.PAYLOAD_FIELD, column(schema, "id").kind());
        assertEquals("id", column(schema, "id").source());
    }

    @Test
    void leavesPayloadFieldsNamedLikeReservedColumnsInThePayload() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("_score", "float"),
            "{\"_id\": \"mongo-id\", \"_payload\": 1, \"_vector\": 2, \"_score\": 0.5,"
                + " \"_group\": 3, \"_lookup\": 4, \"kind\": \"a\"}");

        assertEquals("_id kind _payload _vector", names(schema));
    }

    @Test
    void keepsOtherUnderscoreFieldsAsColumns() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"_ts\": 1, \"id\": \"x\"}");

        assertEquals("_id _ts id _payload _vector", names(schema));
    }

    @Test
    void letsAVectorWinOverAPayloadFieldOfTheSameName() throws Exception {
        TableSchema schema = infer(List.of(new VectorField("dense", VectorKind.DENSE, 4, "Dot")),
            Map.of(), "{\"dense\": \"text\", \"kind\": \"a\"}");

        assertEquals("_id kind _payload dense", names(schema));
        assertEquals(ColumnKind.VECTOR, column(schema, "dense").kind());
    }

    @Test
    void namesAndOrdersVectorColumns() throws Exception {
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
    }

    @Test
    void makesOnlyTheIdNotNull() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"a\": 1}");

        assertFalse(column(schema, "_id").nullable());
        assertTrue(column(schema, "a").nullable());
        assertTrue(column(schema, "_payload").nullable());
    }

    @Test
    void checksIndexTypesAgainstSampledValues() {
        assertEquals(ColumnType.INTEGER,
                SchemaInference.typeOf("integer", EnumSet.of(ValueKind.INTEGER)));
        assertEquals(ColumnType.FLOAT,
                SchemaInference.typeOf("integer", EnumSet.of(ValueKind.INTEGER, ValueKind.FLOAT)));
        assertEquals(ColumnType.JSON,
                SchemaInference.typeOf("keyword", EnumSet.of(ValueKind.INTEGER)));
        assertEquals(ColumnType.JSON,
                SchemaInference.typeOf("bool", EnumSet.of(ValueKind.STRING)));
        assertEquals(ColumnType.JSON,
                SchemaInference.typeOf("keyword", EnumSet.of(ValueKind.ARRAY)));
        assertEquals(ColumnType.JSON,
                SchemaInference.typeOf("geo", EnumSet.noneOf(ValueKind.class)));
        assertEquals(ColumnType.TEXT,
                SchemaInference.typeOf("uuid", EnumSet.noneOf(ValueKind.class)));
        assertEquals(ColumnType.TEXT,
                SchemaInference.typeOf("future_index_type", EnumSet.of(ValueKind.STRING)));
        assertEquals(ColumnType.TEXT,
                SchemaInference.typeOf(null, EnumSet.noneOf(ValueKind.class)));
    }

    @Test
    void ignoresPointsWithoutPayload() {
        TableSchema schema = SchemaInference.infer(
            new CollectionInfo("test", 1, List.of(UNNAMED), Map.of()),
            List.of(new Point(JSON.getNodeFactory().numberNode(1), null, null)));

        assertEquals("_id _payload _vector", names(schema));
    }

    @Test
    void addsIndexedArrayPathsWithoutSamplingThem() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("variants[].sku", "keyword"),
            "{\"variants\": [{\"sku\": \"A1\"}]}");

        assertEquals("_id variants variants[].sku _payload _vector", names(schema));
        assertEquals(ColumnType.TEXT, type(schema, "variants[].sku"));
        assertEquals("Nested payload field (keyword index)",
            column(schema, "variants[].sku").remarks());
    }

    @Test
    void typesNestedIndexedFieldsFromTheirSampledValues() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("specs.weight", "integer"),
            "{\"specs\": {\"weight\": 1.5}}", "{\"specs\": {}}", "{\"other\": 1}");

        assertEquals(ColumnType.FLOAT, type(schema, "specs.weight"));
    }

    @Test
    void fallsBackToJsonWhenIndexAndValuesDisagree() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED),
            Map.of("created_at", "datetime", "title", "text", "flag", "bool"),
            "{\"created_at\": 1700000000, \"title\": \"Hello\", \"flag\": \"yes\"}");

        assertEquals(ColumnType.JSON, type(schema, "created_at"));
        assertEquals(ColumnType.TEXT, type(schema, "title"));
        assertEquals(ColumnType.JSON, type(schema, "flag"));
    }

    @Test
    void usesSampledValuesForUnknownIndexTypes() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("rank", "unrecognized"),
            "{\"rank\": 3}");

        assertEquals(ColumnType.INTEGER, type(schema, "rank"));
        assertEquals("Payload field (unrecognized index)", column(schema, "rank").remarks());
    }

    @Test
    void treatsFieldsThatAreAlwaysNullAsText() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"note\": null}");

        assertEquals(ColumnType.TEXT, type(schema, "note"));
    }

    @Test
    void readsWholeNumbersWrittenWithADecimalPointAsFloats() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of(), "{\"score\": 1.0}");

        assertEquals(ColumnType.FLOAT, type(schema, "score"));
    }

    @Test
    void sortsPayloadFieldsByName() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of("b.c", "keyword"),
            "{\"c\": 1, \"B\": 2, \"a\": 3, \"b\": {\"c\": \"x\"}}");

        assertEquals("_id B a b b.c c _payload _vector", names(schema));
    }

    @Test
    void namesTheUnnamedVectorAfterItsKind() throws Exception {
        TableSchema schema = infer(List.of(
            new VectorField("", VectorKind.MULTIVECTOR, 128, "Cosine")), Map.of());

        assertEquals(ColumnType.MULTIVECTOR, type(schema, "_vector"));
        assertEquals("", column(schema, "_vector").source());
        assertEquals(128, column(schema, "_vector").size());
    }

    @Test
    void keepsTheCollectionInTheSchema() throws Exception {
        TableSchema schema = infer(List.of(UNNAMED), Map.of());

        assertEquals("test", schema.name());
        assertEquals(List.of(UNNAMED), schema.collection().vectors());
    }

    private static TableSchema infer(List<VectorField> vectors, Map<String, String> indexes,
            String... payloads) throws JsonProcessingException {
        List<Point> sample = new ArrayList<>();
        for (int i = 0; i < payloads.length; i++) {
            sample.add(new Point(JSON.getNodeFactory().numberNode(i + 1),
                    JSON.readTree(payloads[i]), null));
        }
        return SchemaInference.infer(new CollectionInfo("test", sample.size(), vectors, indexes),
                sample);
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
