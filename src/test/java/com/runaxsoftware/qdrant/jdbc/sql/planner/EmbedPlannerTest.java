package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.embedding.EmbeddingModel;
import com.runaxsoftware.qdrant.jdbc.embedding.Provider;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.EmbedText;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.Map;

class EmbedPlannerTest {

    private static final List<VectorField> VECTORS = List.of(
            new VectorField("dense", VectorKind.DENSE, 384, "Cosine"),
            new VectorField("keywords", VectorKind.SPARSE, 0, null));

    private static final List<ColumnDefinition> COLUMNS = List.of(
            new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
            new ColumnDefinition("lang", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "lang", ""),
            new ColumnDefinition("dense", ColumnKind.VECTOR, ColumnType.VECTOR, 384, "dense", ""),
            new ColumnDefinition("keywords", ColumnKind.VECTOR, ColumnType.SPARSE_VECTOR, 0, "keywords", ""));

    // No model kept in the collection's metadata
    private static final TableSchema PLAIN = new TableSchema(
            new QdrantCollection("docs", 2, VECTORS, Map.of()), COLUMNS);

    // The model each vector was embedded with, kept in the collection's metadata
    private static final TableSchema KEPT = new TableSchema(
            new QdrantCollection("docs", 2, VECTORS, Map.of(), Map.of(
                    "runax.embed.dense", "openai:text-embedding-3-small",
                    "runax.embed.keywords", "qdrant:qdrant/bm25")), COLUMNS);

    private final Planner planner = new Planner();

    @Test
    void embedsWithTheModelTheStatementNames() throws SQLException {
        QueryPlan plan = query(PLAIN, "SELECT _id, _score FROM docs WHERE lang = 'en' "
                + "ORDER BY similarity(dense, embed('refund policy', 'openai:text-embedding-3-small')) "
                + "DESC LIMIT 5");

        assertEquals(new EmbedText(new EmbeddingModel(Provider.OPENAI, "text-embedding-3-small"),
                "refund policy", 384), plan.embed());
        assertEquals("dense", plan.vector());
        assertEquals(List.of(), plan.query());
    }

    @Test
    void leavesTheTextToQdrantWithAQdrantModelOfAnyKindOfVector() throws SQLException {
        QueryPlan plan = query(PLAIN, "SELECT _id FROM docs "
                + "ORDER BY similarity(keywords, EMBED('refund', 'qdrant:qdrant/bm25')) DESC LIMIT 5");

        assertEquals(new EmbedText(new EmbeddingModel(Provider.QDRANT, "qdrant/bm25"), "refund", 0),
                plan.embed());
    }

    @Test
    void embedsWithTheModelTheCollectionKeepsForTheVector() throws SQLException {
        assertEquals("openai:text-embedding-3-small", query(KEPT, "SELECT _id FROM docs "
                + "ORDER BY similarity(dense, embed('refund')) DESC LIMIT 5").embed().model().id());
        assertEquals("qdrant:qdrant/bm25", query(KEPT, "SELECT _id FROM docs "
                + "ORDER BY similarity(keywords, embed('refund')) DESC LIMIT 5").embed().model().id());
        assertEquals("openai:text-embedding-3-small", query(KEPT, "SELECT _id FROM docs "
                        + "ORDER BY similarity(dense, embed('refund', 'openai:text-embedding-3-small')) "
                        + "DESC LIMIT 5").embed().model().id(),
                "naming the kept model is fine");
    }

    @Test
    void rejectsAModelOtherThanTheOneTheCollectionKeeps() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class, () -> query(KEPT,
                "SELECT _id FROM docs ORDER BY similarity(dense, "
                        + "embed('refund', 'voyage:voyage-3')) DESC LIMIT 5"));

        assertEquals("Vector 'dense' of docs is embedded with openai:text-embedding-3-small, as its "
                + "runax.embed.dense metadata says, so searching it with voyage:voyage-3 would find "
                + "the wrong points: leave the model out, or name openai:text-embedding-3-small",
                error.getMessage());
    }

    @Test
    void asksForTheModelWhenNoneIsNamedOrKept() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class, () -> query(PLAIN,
                "SELECT _id FROM docs ORDER BY similarity(dense, embed('refund')) DESC LIMIT 5"));

        assertEquals("embed needs the model that embeds vector 'dense' of docs: name it, as "
                + "embed('refund', 'openai:text-embedding-3-small'), or keep it in the collection's "
                + "runax.embed.dense metadata", error.getMessage());
    }

    @Test
    void rejectsADriverProviderForASparseVector() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> query(PLAIN, "SELECT _id FROM docs "
                + "ORDER BY similarity(keywords, embed('refund', 'openai:text-embedding-3-small')) "
                + "DESC LIMIT 5"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "embed()",
            "embed('refund', 'openai:x', 'extra')",
            "embed(lang, 'openai:x')",
            "embed('refund', 5)",
            "embed('refund', 'text-embedding-3-small')",
            "embed('refund', 'cohere:embed-v3')",
            "embed('refund', 'openai:')"})
    void rejectsAnEmbedWithoutTextAndAModelItCanRead(String embed) {
        assertThrows(SQLSyntaxErrorException.class, () -> query(PLAIN,
                "SELECT _id FROM docs ORDER BY similarity(dense, " + embed + ") DESC LIMIT 5"));
    }

    @Test
    void reportsAModelInTheMetadataItCantRead() {
        TableSchema broken = new TableSchema(new QdrantCollection("docs", 2, VECTORS, Map.of(),
                Map.of("runax.embed.dense", "text-embedding-3-small")), COLUMNS);

        SQLException error = assertThrows(SQLException.class, () -> query(broken,
                "SELECT _id FROM docs ORDER BY similarity(dense, embed('refund')) DESC LIMIT 5"));

        assertEquals(true, error.getMessage().startsWith("The runax.embed.dense metadata of docs "
                + "holds 'text-embedding-3-small', which isn't a model"), error.getMessage());
    }

    @Test
    void saysHowASparseVectorIsWrittenWhenItIsGivenADenseOne() {
        SQLException error = assertThrows(SQLException.class,
                () -> query(PLAIN, "SELECT _id FROM docs ORDER BY similarity(keywords, [0.1]) DESC LIMIT 5"));

        assertEquals("Vector 'keywords' of docs takes a sparse vector, as "
                + "'{\"indices\": [1, 7], \"values\": [0.5, 0.9]}', or embed('text')",
                error.getMessage());
    }

    // The size a model ID asks for

    @Test
    void embedsWithTheSizeTheModelIdAsksForWhenItFitsTheVector() throws SQLException {
        QueryPlan plan = query(PLAIN, "SELECT _id FROM docs ORDER BY similarity(dense, "
                + "embed('refund policy', 'openai:text-embedding-3-large?dimensions=384')) DESC LIMIT 5");

        assertEquals(new EmbedText(
                new EmbeddingModel(Provider.OPENAI, "text-embedding-3-large", 384),
                "refund policy", 384), plan.embed());
    }

    /**
     * The size is known before the provider is called, so a vector that couldn't be stored is an
     * error now rather than after paying to embed it.
     */
    @Test
    void refusesASizeTheVectorCantHoldBeforeCallingTheProvider() {
        SQLException error = assertThrows(SQLException.class,
                () -> query(PLAIN, "SELECT _id FROM docs ORDER BY similarity(dense, "
                        + "embed('refund policy', 'openai:text-embedding-3-large?dimensions=1024')) "
                        + "DESC LIMIT 5"));

        assertEquals("42000", error.getSQLState());
        assertTrue(error.getMessage().contains("1024"), error.getMessage());
        assertTrue(error.getMessage().contains("384"), error.getMessage());
    }

    /**
     * A size makes a different model, so one that doesn't match the collection's metadata is
     * caught by the check that already compares them.
     */
    @Test
    void refusesASizeTheCollectionsModelDoesntAskFor() {
        SQLException error = assertThrows(SQLException.class,
                () -> query(KEPT, "SELECT _id FROM docs ORDER BY similarity(dense, "
                        + "embed('refund policy', 'openai:text-embedding-3-small?dimensions=384')) "
                        + "DESC LIMIT 5"));

        assertEquals("42000", error.getSQLState());
    }

    private QueryPlan query(TableSchema schema, String sql) throws SQLException {
        return (QueryPlan) planner.plan(Parser.parse(sql), name -> schema);
    }
}
