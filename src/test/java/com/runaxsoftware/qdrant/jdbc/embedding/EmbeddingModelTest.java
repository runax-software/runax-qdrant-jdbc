package com.runaxsoftware.qdrant.jdbc.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;

class EmbeddingModelTest {

    @Test
    void readsTheProviderAndKeepsTheModelsOwnName() throws SQLException {
        assertEquals(new EmbeddingModel(Provider.OPENAI, "text-embedding-3-small"),
            EmbeddingModel.parse("openai:text-embedding-3-small"));
        assertEquals(new EmbeddingModel(Provider.QDRANT, "sentence-transformers/all-minilm-l6-v2"),
            EmbeddingModel.parse("qdrant:sentence-transformers/all-minilm-l6-v2"));
        assertEquals(new EmbeddingModel(Provider.VOYAGE, "voyage-3"),
            EmbeddingModel.parse("Voyage:voyage-3"), "the provider in any case");
        assertEquals("qdrant:qdrant/bm25", EmbeddingModel.parse("qdrant:qdrant/bm25").id());
    }

    // The size a model ID may ask for

    @Test
    void readsTheSizeAModelIdAsksFor() throws SQLException {
        assertEquals(new EmbeddingModel(Provider.OPENAI, "text-embedding-3-large", 1024),
            EmbeddingModel.parse("openai:text-embedding-3-large?dimensions=1024"));
        assertEquals(0, EmbeddingModel.parse("openai:text-embedding-3-small").dimensions(),
            "no size asked for is the model's own");
    }

    /**
     * A model name may hold slashes, so the size is found by the '?' and not by splitting.
     */
    @Test
    void readsTheSizeAfterAModelNameThatHoldsSlashes() throws SQLException {
        assertEquals(new EmbeddingModel(Provider.QDRANT, "sentence-transformers/all-minilm-l6-v2", 256),
            EmbeddingModel.parse("qdrant:sentence-transformers/all-minilm-l6-v2?dimensions=256"));
    }

    @Test
    void writesTheSizeBackSoAnIdRoundTrips() throws SQLException {
        String id = "openai:text-embedding-3-large?dimensions=1024";

        assertEquals(id, EmbeddingModel.parse(id).id());
        assertEquals("openai:text-embedding-3-small",
            EmbeddingModel.parse("openai:text-embedding-3-small").id(), "no size, nothing written");
    }

    /**
     * Two IDs that ask for different sizes are different models: their vectors aren't comparable,
     * so a search must not accept one where the points were stored with the other.
     */
    @Test
    void countsTheSizeAsPartOfTheModel() throws SQLException {
        assertNotEquals(EmbeddingModel.parse("openai:m?dimensions=512"),
            EmbeddingModel.parse("openai:m?dimensions=256"));
        assertNotEquals(EmbeddingModel.parse("openai:m?dimensions=512"),
            EmbeddingModel.parse("openai:m"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "openai:m?dimensions=0",
        "openai:m?dimensions=-1",
        "openai:m?dimensions=many",
        "openai:m?dimensions=",
        "openai:m?size=512",
        "openai:m?dimensions=512&x=1"})
    void rejectsASizeAProviderCouldntReturn(String id) {
        assertEquals("42000", assertThrows(SQLSyntaxErrorException.class,
            () -> EmbeddingModel.parse(id)).getSQLState(), id);
    }

    @Test
    void namesTheMetadataEntryForAVector() {
        assertEquals("runax.embed.dense", EmbeddingModel.metadataKey("dense"));
        assertEquals("runax.embed._vector", EmbeddingModel.metadataKey("_vector"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text-embedding-3-small", ":model", "openai:", "cohere:embed-v3"})
    void rejectsWhatIsntAProviderAndModel(String id) {
        assertEquals("42000", assertThrows(SQLSyntaxErrorException.class,
            () -> EmbeddingModel.parse(id)).getSQLState());
    }
}
