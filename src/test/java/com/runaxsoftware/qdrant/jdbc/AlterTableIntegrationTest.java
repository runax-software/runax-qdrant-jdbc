package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
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
import java.util.UUID;

/**
 * Adds and drops named vectors on a real server with ALTER TABLE, and reads and writes the
 * columns that come and go. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class AlterTableIntegrationTest {

    private String named;
    private String unnamed;
    private QdrantGateway gateway;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void createCollections() throws SQLException {
        String prefix = "jdbc_alter_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        named = prefix + "named";
        unnamed = prefix + "unnamed";
        gateway = TestQdrant.gateway();
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
        statement = connection.createStatement();

        statement.executeUpdate("CREATE TABLE " + named + " (dense VECTOR(2))");
        statement.executeUpdate("INSERT INTO " + named + " (_id, title, dense) VALUES "
            + "(1, 'Dune', [1, 0]), (2, 'Emma', [0, 1])");
        statement.executeUpdate("CREATE TABLE " + unnamed + " (_vector VECTOR(2))");
        statement.executeUpdate("INSERT INTO " + unnamed + " (_id, title, _vector) VALUES "
            + "(1, 'Dune', [1, 0])");
    }

    @AfterEach
    void deleteCollections() throws SQLException {
        statement.close();
        connection.close();
        gateway.deleteCollection(named);
        gateway.deleteCollection(unnamed);
        gateway.close();
    }

    @Test
    void addsADenseVectorThatTheNextStatementWritesAndSearches() throws SQLException {
        // Read first, so the driver holds the collection's columns from before the change
        assertFalse(columns("SELECT * FROM " + named).contains("image"));

        assertEquals(0, statement.executeUpdate(
            "ALTER TABLE " + named + " ADD COLUMN image VECTOR(3) DISTANCE DOT"));

        assertEquals(new VectorField("image", VectorKind.DENSE, 3, "Dot"), vector(named, "image"));

        assertEquals(1, statement.executeUpdate(
            "UPDATE " + named + " SET image = [1, 2, 3] WHERE _id = 1"));
        assertTrue(columns("SELECT image FROM " + named).contains("image"));

        try (ResultSet rows = statement.executeQuery("SELECT _id FROM " + named
            + " WHERE image IS NOT NULL ORDER BY similarity(image, [1, 2, 3]) DESC LIMIT 5")) {
            assertTrue(rows.next());
            assertEquals("1", rows.getString("_id"));
            assertFalse(rows.next(), "the other point has no image vector");
        }
    }

    @Test
    void addsASparseVectorAndAMultivector() throws SQLException {
        statement.executeUpdate(
            "ALTER TABLE " + named + " ADD COLUMN keywords SPARSE_VECTOR MODIFIER IDF");
        statement.executeUpdate("ALTER TABLE " + named + " ADD colbert MULTIVECTOR(4)");

        assertEquals(VectorKind.SPARSE, vector(named, "keywords").kind());
        assertEquals(new VectorField("colbert", VectorKind.MULTIVECTOR, 4, "Cosine"),
            vector(named, "colbert"));
    }

    @Test
    void refusesAVectorNameThatsTakenUnlessIfNotExists() throws SQLException {
        SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate(
            "ALTER TABLE " + named + " ADD COLUMN dense VECTOR(2)"));
        assertEquals("42S21", error.getSQLState());

        assertEquals(0, statement.executeUpdate(
            "ALTER TABLE " + named + " ADD COLUMN IF NOT EXISTS dense VECTOR(9) DISTANCE DOT"));
        assertEquals(new VectorField("dense", VectorKind.DENSE, 2, "Cosine"),
            vector(named, "dense"), "the vector that was there is untouched");
    }

    @Test
    void dropsAVectorAndItsColumn() throws SQLException {
        statement.executeUpdate("ALTER TABLE " + named + " ADD COLUMN image VECTOR(3)");
        statement.executeUpdate("UPDATE " + named + " SET image = [1, 2, 3] WHERE _id = 1");

        assertEquals(0, statement.executeUpdate("ALTER TABLE " + named + " DROP COLUMN image"));

        assertEquals(null, vector(named, "image"));
        assertEquals("42S22", assertThrows(SQLException.class, () -> statement.executeQuery(
            "SELECT image FROM " + named)).getSQLState(), "its column went with it");

        try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + named)) {
            assertTrue(rows.next());
            assertEquals(2, rows.getLong(1), "the points stay");
        }
    }

    @Test
    void reportsAMissingVectorUnlessIfExists() throws SQLException {
        SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate(
            "ALTER TABLE " + named + " DROP COLUMN title"));
        assertEquals("42S22", error.getSQLState());
        assertTrue(error.getMessage().contains("its named vectors are dense"), error.getMessage());

        assertEquals(0, statement.executeUpdate(
            "ALTER TABLE " + named + " DROP COLUMN IF EXISTS title"));

        try (ResultSet rows = statement.executeQuery(
            "SELECT title FROM " + named + " WHERE _id = 1")) {
            assertTrue(rows.next());
            assertEquals("Dune", rows.getString("title"), "the payload field is untouched");
        }
    }

    @Test
    void addsANamedVectorBesideAnUnnamedOne() throws SQLException {
        statement.executeUpdate("ALTER TABLE " + unnamed + " ADD COLUMN image VECTOR(3)");

        statement.executeUpdate("UPDATE " + unnamed + " SET image = [1, 2, 3] WHERE _id = 1");
        statement.executeUpdate("INSERT INTO " + unnamed + " (_id, title, _vector, image) VALUES "
            + "(2, 'Emma', [0, 1], [3, 2, 1])");

        try (ResultSet rows = statement.executeQuery("SELECT _id, _vector, image FROM " + unnamed
            + " ORDER BY similarity(image, [3, 2, 1]) DESC LIMIT 5")) {
            assertTrue(rows.next());
            assertEquals("2", rows.getString("_id"));
            assertTrue(rows.getString("_vector").startsWith("[0"), rows.getString("_vector"));
            assertTrue(rows.next());
            assertFalse(rows.next());
        }

        try (ResultSet rows = statement.executeQuery("SELECT _id FROM " + unnamed
            + " ORDER BY similarity(_vector, [1, 0]) DESC LIMIT 1")) {
            assertTrue(rows.next());
            assertEquals("1", rows.getString("_id"), "the unnamed vector still searches");
        }
    }

    @Test
    void refusesAlterTableOnAReadOnlyConnection() throws SQLException {
        connection.setReadOnly(true);

        assertEquals("25006", assertThrows(SQLException.class, () -> statement.executeUpdate(
            "ALTER TABLE " + named + " ADD COLUMN image VECTOR(3)")).getSQLState());
        assertEquals("25006", assertThrows(SQLException.class, () -> statement.executeUpdate(
            "ALTER TABLE " + named + " DROP COLUMN dense")).getSQLState());
        assertEquals(null, vector(named, "image"));
    }

    @Test
    void saysItAltersTablesInTheMetadata() throws SQLException {
        assertTrue(connection.getMetaData().supportsAlterTableWithAddColumn());
        assertTrue(connection.getMetaData().supportsAlterTableWithDropColumn());
    }

    private VectorField vector(String collection, String name) throws SQLException {
        return gateway.collection(collection).vectors().stream()
            .filter(vector -> vector.name().equals(name)).findFirst().orElse(null);
    }

    private List<String> columns(String sql) throws SQLException {
        List<String> names = new ArrayList<>();

        try (ResultSet rows = statement.executeQuery(sql)) {
            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                names.add(rows.getMetaData().getColumnLabel(i));
            }
        }

        return names;
    }
}
