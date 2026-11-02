package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.embedding.EmbeddingModel;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.EmbedText;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConflictAction;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.InsertPointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.NewPoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class InsertPlannerTest {

    // Named dense, multi-, and sparse vectors, and typed payload fields
    private static final TableSchema DOCS = new TableSchema(
            new QdrantCollection("docs", 0, List.of(
                    new VectorField("dense", VectorKind.DENSE, 2, "Cosine"),
                    new VectorField("colbert", VectorKind.MULTIVECTOR, 2, "Cosine"),
                    new VectorField("keywords", VectorKind.SPARSE, 0, null)), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("_payload", ColumnKind.PAYLOAD, ColumnType.JSON, 0, null, ""),
                    new ColumnDefinition("lang", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "lang", ""),
                    new ColumnDefinition("year", ColumnKind.PAYLOAD_FIELD, ColumnType.INTEGER, 0, "year", ""),
                    new ColumnDefinition("specs.color", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "specs.color", ""),
                    new ColumnDefinition("dense", ColumnKind.VECTOR, ColumnType.VECTOR, 2, "dense", ""),
                    new ColumnDefinition("colbert", ColumnKind.VECTOR, ColumnType.MULTIVECTOR, 2, "colbert", ""),
                    new ColumnDefinition("keywords", ColumnKind.VECTOR, ColumnType.SPARSE_VECTOR, 0, "keywords", "")));

    // The collection's single unnamed vector
    private static final TableSchema PLAIN = new TableSchema(
            new QdrantCollection("plain", 0, List.of(new VectorField("", VectorKind.DENSE, 2, "Cosine")), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("_vector", ColumnKind.VECTOR, ColumnType.VECTOR, 2, "", "")));

    private final Planner planner = new Planner();

    @Test
    void makesAPointOfEachRow() throws SQLException {
        InsertPointsPlan plan = insert(DOCS, "INSERT INTO docs (_id, lang, year, dense) VALUES "
                + "(1, 'en', 2024, [0.1, 0.2]), ('5C56C793-69F3-4FBF-87E6-C4BF54C28C26', 'de', '2025', NULL)");

        assertEquals("docs", plan.collection());
        assertEquals(2, plan.points().size());

        NewPoint first = plan.points().get(0);
        assertEquals("1", first.id().toString());
        assertTrue(first.idGiven());
        assertEquals("{\"lang\":\"en\",\"year\":2024}", first.payload().toString());
        assertEquals(Map.of("dense", "[0.1,0.2]"), text(first.vectors()));

        NewPoint second = plan.points().get(1);
        assertEquals("\"5c56c793-69f3-4fbf-87e6-c4bf54c28c26\"", second.id().toString(), "as Qdrant writes it");
        assertEquals("{\"lang\":\"de\",\"year\":2025}", second.payload().toString(), "the text as the column's type");
        assertEquals(Map.of(), second.vectors(), "a NULL vector is left out");
    }

    @Test
    void givesARowWithoutAnIdANewUuid() throws SQLException {
        NewPoint point = insert(DOCS, "INSERT INTO docs (lang) VALUES ('en')").points().get(0);

        assertFalse(point.idGiven());
        UUID.fromString(point.id().asText());
        assertEquals("1", insert(DOCS, "INSERT INTO docs (_id) VALUES ('1')").points().get(0).id().toString(),
                "an ID written as text, as a tool sends it");
    }

    @Test
    void buildsTheObjectsOfNestedFieldsAndMergesThemIntoAWholePayload() throws SQLException {
        NewPoint point = insert(DOCS, "INSERT INTO docs (\"specs.color\", _payload, rating) VALUES "
                + "('red', '{\"specs\": {\"weight\": 2}, \"lang\": \"en\"}', 5)").points().get(0);

        assertEquals("{\"specs\":{\"weight\":2,\"color\":\"red\"},\"lang\":\"en\",\"rating\":5}",
                point.payload().toString());
    }

    @Test
    void readsVectorsGivenAsJsonText() throws SQLException {
        NewPoint point = insert(DOCS, "INSERT INTO docs (dense, colbert, keywords) VALUES ('[1, 0]', "
                + "'[[0.1, 0.2], [0.3, 0.4]]', '{\"indices\": [1, 7], \"values\": [0.5, 0.9]}')").points().get(0);

        assertEquals(Map.of("dense", "[1.0,0.0]", "colbert", "[[0.1,0.2],[0.3,0.4]]",
                "keywords", "{\"indices\":[1,7],\"values\":[0.5,0.9]}"), text(point.vectors()));
    }

    @Test
    void putsTheUnnamedVectorUnderNoName() throws SQLException {
        assertEquals(Map.of("", "[0.1,0.2]"), text(insert(PLAIN,
                "INSERT INTO plain (_id, _vector) VALUES (1, [0.1, 0.2])").points().get(0).vectors()));
    }

    @Test
    void embedsTextWithQdrantOrLeavesItToTheDriver() throws SQLException {
        NewPoint point = insert(DOCS, "INSERT INTO docs (keywords, dense) VALUES "
                + "(embed('refund policy', 'qdrant:qdrant/bm25'), embed('refund', 'openai:text-embedding-3-small'))")
                .points().get(0);

        assertEquals(Map.of("keywords", "{\"text\":\"refund policy\",\"model\":\"qdrant/bm25\"}"),
                text(point.vectors()), "Qdrant embeds as it stores the point");
        assertEquals(Map.of("dense", new EmbedText(EmbeddingModel.parse("openai:text-embedding-3-small"),
                "refund", 2)), point.embeds(), "the driver embeds before it inserts");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO docs (_id) VALUES (-1)",
            "INSERT INTO docs (_id) VALUES ('not-an-id')",
            "INSERT INTO docs (year) VALUES ('soon')",
            "INSERT INTO docs (dense) VALUES ([0.1, 0.2, 0.3])",
            "INSERT INTO docs (dense) VALUES ('[1, \"a\"]')",
            "INSERT INTO docs (colbert) VALUES ('[[1, 2, 3]]')",
            "INSERT INTO docs (keywords) VALUES ('[0.1]')",
            "INSERT INTO docs (keywords) VALUES ([0.1, 0.2])",
            "INSERT INTO docs (_payload) VALUES ('[1]')",
            "INSERT INTO docs (lang, \"lang.x\") VALUES ('en', 1)"})
    void rejectsValuesTheColumnsCantHold(String sql) {
        assertEquals("22018", assertThrows(SQLException.class, () -> insert(DOCS, sql)).getSQLState(), sql);
    }

    @Test
    void rejectsRowsAndColumnsThatDontAddUp() {
        assertEquals("21S01", assertThrows(SQLException.class,
                () -> insert(DOCS, "INSERT INTO docs (_id, lang) VALUES (1, 'en'), (2)")).getSQLState());
        assertEquals("42000", assertThrows(SQLException.class,
                () -> insert(DOCS, "INSERT INTO docs (lang, lang) VALUES ('a', 'b')")).getSQLState());
        assertEquals("23505", assertThrows(SQLException.class,
                () -> insert(DOCS, "INSERT INTO docs (_id) VALUES (1), ('1')")).getSQLState());
        assertEquals("42000", assertThrows(SQLException.class,
                () -> insert(DOCS, "INSERT INTO docs (_score) VALUES (1)")).getSQLState());
        assertEquals("42000", assertThrows(SQLException.class,
                () -> insert(DOCS, "INSERT INTO docs (_rank) VALUES (1)")).getSQLState());
        assertThrows(SQLException.class, () -> insert(DOCS, "INSERT INTO docs (lang) VALUES (year)"));
    }

    // ON CONFLICT

    @Test
    void failsOnATakenIdWhenTheStatementSaysNothing() throws SQLException {
        InsertPointsPlan plan = insert(DOCS, "INSERT INTO docs (_id, lang) VALUES (1, 'en')");

        assertEquals(ConflictAction.FAIL, plan.onConflict());
        assertNull(plan.updateFilter());
        assertTrue(plan.onConflict().checksIds(), "the IDs are looked up first");
        assertFalse(plan.onConflict().replaces());
    }

    /**
     * DO NOTHING is what Qdrant's insert_only mode already does to a point that's there, so the
     * only difference from a plain INSERT is that the IDs need no looking up.
     */
    @Test
    void skipsAPointThatIsThereWithoutLookingTheIdsUp() throws SQLException {
        InsertPointsPlan plan = insert(DOCS,
                "INSERT INTO docs (_id, lang) VALUES (1, 'en') ON CONFLICT (_id) DO NOTHING");

        assertEquals(ConflictAction.NOTHING, plan.onConflict());
        assertFalse(plan.onConflict().checksIds());
        assertFalse(plan.onConflict().replaces(), "insert_only, as a plain INSERT");
        assertNull(plan.updateFilter());
    }

    @Test
    void replacesAPointThatIsThereOnDoUpdate() throws SQLException {
        InsertPointsPlan plan = insert(DOCS,
                "INSERT INTO docs (_id, lang) VALUES (1, 'en') ON CONFLICT (_id) DO UPDATE");

        assertEquals(ConflictAction.UPDATE, plan.onConflict());
        assertFalse(plan.onConflict().checksIds());
        assertTrue(plan.onConflict().replaces(), "Qdrant's upsert mode");
        assertNull(plan.updateFilter());
    }

    /**
     * The WHERE of a DO UPDATE reads the stored point, as a SELECT's does, and becomes Qdrant's
     * update_filter.
     */
    @Test
    void plansTheConditionThatGuardsADoUpdateAsAFilter() throws SQLException {
        InsertPointsPlan plan = insert(DOCS, "INSERT INTO docs (_id, lang) VALUES (1, 'en') "
                + "ON CONFLICT (_id) DO UPDATE WHERE year < 3");

        assertEquals(ConflictAction.UPDATE, plan.onConflict());
        assertEquals("{\"must\":[{\"key\":\"year\",\"range\":{\"lt\":3}}]}",
                plan.updateFilter().toString());
    }

    @Test
    void reportsAConditionTheFilterCantExpress() {
        assertThrows(SQLException.class, () -> insert(DOCS,
                "INSERT INTO docs (_id) VALUES (1) ON CONFLICT (_id) DO UPDATE WHERE missing = 1"));
    }

    private InsertPointsPlan insert(TableSchema schema, String sql) throws SQLException {
        return (InsertPointsPlan) planner.plan(Parser.parse(sql), name -> schema);
    }

    private static Map<String, String> text(Map<String, ?> vectors) {
        return vectors.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, x -> x.getValue().toString()));
    }
}
