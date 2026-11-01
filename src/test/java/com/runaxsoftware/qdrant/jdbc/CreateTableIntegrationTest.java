package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates collections in real Qdrant and reads back what Qdrant made of them. Needs Qdrant:
 * {@code docker compose up -d}.
 */
@Tag("integration")
class CreateTableIntegrationTest {

    private String prefix;
    private QdrantGateway gateway;
    private Connection connection;

    @BeforeEach
    void connect() throws SQLException {
        prefix = "jdbc_create_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        gateway = TestQdrant.gateway();
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
    }

    @AfterEach
    void deleteCollections() throws SQLException {
        connection.close();

        for (String name : gateway.listCollections()) {
            if (name.startsWith(prefix)) gateway.deleteCollection(name);
        }
        gateway.close();
    }

    @Test
    void createsVectorsAndPayloadIndexes() throws SQLException {
        String docs = prefix + "docs";

        try (Statement statement = connection.createStatement()) {
            assertEquals(0, statement.executeUpdate("CREATE TABLE " + docs + " ("
                + "dense VECTOR(4) DISTANCE DOT, colbert MULTIVECTOR(2), "
                + "keywords SPARSE_VECTOR MODIFIER IDF, lang KEYWORD INDEX, year INTEGER INDEX, "
                + "\"author.name\" KEYWORD INDEX)"));
        }

        JsonNode params = gateway.collectionInfo(docs).path("config").path("params");
        assertEquals(4, params.path("vectors").path("dense").path("size").asInt());
        assertEquals("Dot", params.path("vectors").path("dense").path("distance").asText());
        assertEquals("max_sim", params.path("vectors").path("colbert")
            .path("multivector_config").path("comparator").asText());
        assertEquals("idf", params.path("sparse_vectors").path("keywords").path("modifier").asText());

        assertEquals(Map.of("lang", PayloadIndexType.KEYWORD, "year", PayloadIndexType.INTEGER,
            "author.name", PayloadIndexType.KEYWORD), gateway.collection(docs).payloadIndexes());
    }

    @Test
    void createsAnUnnamedVectorThatReadsAsTheVectorColumn() throws SQLException {
        String docs = prefix + "plain";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE " + docs + " (_vector VECTOR(3))");
        }

        List<String> columns = new ArrayList<>();
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, docs, "%")) {
            while (rows.next()) columns.add(rows.getString("COLUMN_NAME") + " "
                + rows.getString("TYPE_NAME"));
        }

        assertTrue(columns.contains("_vector VECTOR"), columns.toString());
        assertEquals("Cosine", gateway.collectionInfo(docs).path("config").path("params")
            .path("vectors").path("distance").asText(), "the default distance");
    }

    @Test
    void createsIfNotExistsOnlyOnce() throws SQLException {
        String docs = prefix + "once";
        String sql = "CREATE TABLE " + docs + " (_vector VECTOR(2) DISTANCE COSINE)";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);

            SQLException exists = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
            assertTrue(exists.getMessage().contains("already exists"), exists.getMessage());

            assertEquals(0, statement.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + docs + " (_vector VECTOR(8))"));
        }

        assertEquals(2, gateway.collectionInfo(docs).path("config").path("params")
            .path("vectors").path("size").asInt(), "the existing collection was kept as it was");
    }

    @Test
    void readsANewCollectionThatADroppedOneLeftItsColumnsFor() throws SQLException {
        String docs = prefix + "again";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE " + docs + " (dense VECTOR(2))");
            statement.executeQuery("SELECT * FROM " + docs).close();
            statement.executeUpdate("DROP TABLE " + docs);
            statement.executeUpdate("CREATE TABLE " + docs + " (other VECTOR(2))");

            try (ResultSet rows = statement.executeQuery("SELECT _id, other FROM " + docs)) {
                assertFalse(rows.next(), "a new, empty collection with the new vector's column");
            }
        }
    }

    @Test
    void refusesToCreateOnAReadOnlyConnection() throws SQLException {
        connection.setReadOnly(true);

        try (Statement statement = connection.createStatement()) {
            assertEquals("25006", assertThrows(SQLException.class, () -> statement.executeUpdate(
                "CREATE TABLE " + prefix + "nope (_vector VECTOR(2))")).getSQLState());
        }

        assertFalse(gateway.collectionExists(prefix + "nope"));
    }
}
