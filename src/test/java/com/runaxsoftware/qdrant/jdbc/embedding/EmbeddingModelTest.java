package com.runaxsoftware.qdrant.jdbc.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
