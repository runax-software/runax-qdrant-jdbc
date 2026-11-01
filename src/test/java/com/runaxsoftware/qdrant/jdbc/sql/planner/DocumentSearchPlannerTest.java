package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.runaxsoftware.qdrant.jdbc.gateway.models.Document;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.Map;

class DocumentSearchPlannerTest {

    private static final TableSchema DOCS = new TableSchema(
            new QdrantCollection("docs", 2, List.of(
                    new VectorField("dense", VectorKind.DENSE, 384, "Cosine"),
                    new VectorField("keywords", VectorKind.SPARSE, 0, null)), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("lang", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "lang", ""),
                    new ColumnDefinition("dense", ColumnKind.VECTOR, ColumnType.VECTOR, 384, "dense", ""),
                    new ColumnDefinition("keywords", ColumnKind.VECTOR, ColumnType.SPARSE_VECTOR, 0, "keywords", "")));

    private final Planner planner = new Planner();

    @Test
    void searchesASparseVectorWithTextQdrantEmbeds() throws SQLException {
        QueryPlan plan = query("SELECT _id, _score FROM docs WHERE lang = 'en' "
                + "ORDER BY similarity(keywords, document('refund policy', 'qdrant/bm25')) DESC LIMIT 5");

        assertEquals("keywords", plan.vector());
        assertEquals(new Document("refund policy", "qdrant/bm25"), plan.document());
        assertEquals(List.of(), plan.query());
        assertEquals("{\"must\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}}]}",
                plan.filter().toString());
    }

    @Test
    void searchesADenseVectorWithAHostedModelWithoutCheckingDimensions() throws SQLException {
        QueryPlan plan = query("SELECT _id FROM docs ORDER BY similarity(dense, "
                + "DOCUMENT('refund policy', 'sentence-transformers/all-minilm-l6-v2')) DESC LIMIT 5");

        assertEquals("dense", plan.vector());
        assertEquals("sentence-transformers/all-minilm-l6-v2", plan.document().model(),
                "the model makes the vector, so its dimensions are Qdrant's to check");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "document('refund policy')",
            "document('refund policy', '')",
            "document(lang, 'qdrant/bm25')",
            "document('refund policy', 5)"})
    void rejectsADocumentWithoutTextAndAModel(String document) {
        assertThrows(SQLSyntaxErrorException.class, () -> query(
                "SELECT _id FROM docs ORDER BY similarity(keywords, " + document + ") DESC LIMIT 5"));
    }

    @Test
    void stillSearchesASparseVectorOnlyWithADocument() {
        SQLFeatureNotSupportedException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> query("SELECT _id FROM docs ORDER BY similarity(keywords, [0.1]) DESC LIMIT 5"));

        assertEquals("Searching 'keywords', a SPARSE_VECTOR of docs, with a vector written out "
                + "isn't supported yet: search it with document('text', 'model'), which Qdrant "
                + "embeds", error.getMessage());
    }

    private QueryPlan query(String sql) throws SQLException {
        return (QueryPlan) planner.plan(Parser.parse(sql), name -> DOCS);
    }
}
