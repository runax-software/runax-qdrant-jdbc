package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.Json;
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
 * Manages payload indexes and aliases from SQL in real Qdrant, and checks that what the driver
 * keeps about columns and names follows each change at once. Needs Qdrant:
 * {@code docker compose up -d}.
 */
@Tag("integration")
class IndexesAndViewsIntegrationTest {

    private String prefix;
    private String books;
    private String films;
    private QdrantGateway gateway;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void createCollections() throws SQLException {
        prefix = "jdbc_ddl_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        books = prefix + "books";
        films = prefix + "films";
        gateway = TestQdrant.gateway();
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
        statement = connection.createStatement();

        statement.executeUpdate("CREATE TABLE " + books + " (_vector VECTOR(2))");
        statement.executeUpdate("INSERT INTO " + books + " (_id, title, year, _vector) VALUES "
            + "(1, 'Dune', 1965, [1, 0]), (2, 'Emma', 1815, [0, 1]), (3, 'Ubik', 1969, [1, 1])");
        statement.executeUpdate("CREATE TABLE " + films + " (_vector VECTOR(2))");
        statement.executeUpdate("INSERT INTO " + films + " (_id, title, year, _vector) VALUES "
            + "(10, 'Alien', 1979, [1, 0])");
    }

    @AfterEach
    void deleteCollections() throws SQLException {
        statement.close();
        connection.close();

        for (var alias : gateway.listAliases()) {
            if (alias.name().startsWith(prefix)) {
                gateway.changeAliases(List.of(Json.object("delete_alias",
                    Json.object("alias_name", alias.name()))));
            }
        }
        for (String name : gateway.listCollections()) {
            if (name.startsWith(prefix)) gateway.deleteCollection(name);
        }
        gateway.close();
    }

    @Test
    void anIndexLetsAFieldOrderAReadAtOnce() throws SQLException {
        String ordered = "SELECT title FROM " + books + " ORDER BY year DESC LIMIT 3";
        assertThrows(SQLException.class, () -> column(ordered), "no index on year yet");

        assertEquals(0, statement.executeUpdate("CREATE INDEX ON " + books + " (year)"));
        assertEquals(List.of("Ubik", "Dune", "Emma"), column(ordered),
            "the kept columns follow the new index, without a refresh");

        assertEquals(0, statement.executeUpdate("DROP INDEX year_idx ON " + books));
        assertThrows(SQLException.class, () -> column(ordered), "and follow its removal");
    }

    @Test
    void createsAnIndexWithItsOptionsAndReportsIt() throws SQLException {
        statement.executeUpdate("CREATE INDEX title_idx ON " + books + " (title) USING text "
            + "WITH (tokenizer = word, lowercase = true, phrase_matching = true, stemmer = english)");

        JsonNode params = gateway.collectionInfo(books).path("payload_schema").path("title")
            .path("params");
        assertEquals("word", params.path("tokenizer").asText());
        assertTrue(params.path("phrase_matching").asBoolean());
        assertEquals("english", params.path("stemmer").path("language").asText());

        List<String> indexes = new ArrayList<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(null, null, books, false, false)) {
            while (rows.next()) indexes.add(rows.getString("INDEX_NAME"));
        }
        assertTrue(indexes.contains("title_idx"), indexes.toString());
    }

    @Test
    void refusesToReplaceOrDropWhatIsntThere() throws SQLException {
        statement.executeUpdate("CREATE INDEX ON " + books + " (year)");

        assertEquals("42S11", assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CREATE INDEX ON " + books + " (year) USING float")).getSQLState());
        assertEquals(0, statement.executeUpdate("CREATE INDEX IF NOT EXISTS ON " + books
            + " (year) USING float"));
        assertEquals("integer", gateway.collection(books).payloadIndexes().get("year").qdrantName(),
            "the index was kept, not replaced");

        assertEquals("42S12", assertThrows(SQLException.class, () -> statement.executeUpdate(
            "DROP INDEX title_idx ON " + books)).getSQLState());
        assertEquals(0, statement.executeUpdate("DROP INDEX IF EXISTS ON " + books + " (title)"));
    }

    @Test
    void managesAViewThroughItsWholeLife() throws SQLException {
        String view = prefix + "current";

        statement.executeUpdate("CREATE VIEW " + view + " AS SELECT * FROM " + books);
        assertEquals(List.of("Dune", "Emma", "Ubik"), column("SELECT title FROM " + view));
        assertTrue(tables("VIEW").contains(view), "listed as a view");

        assertEquals("42S01", assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CREATE VIEW " + view + " AS SELECT * FROM " + films)).getSQLState());
        assertEquals(List.of("Dune", "Emma", "Ubik"), column("SELECT title FROM " + view),
            "a plain CREATE didn't move it");

        statement.executeUpdate("CREATE OR REPLACE VIEW " + view + " AS SELECT * FROM " + films);
        assertEquals(List.of("Alien"), column("SELECT title FROM " + view),
            "moved, and read under the view's name again");

        String renamed = prefix + "live";
        statement.executeUpdate("ALTER VIEW " + view + " RENAME TO " + renamed);
        assertEquals("42S02", assertThrows(SQLException.class,
            () -> column("SELECT title FROM " + view)).getSQLState());
        assertEquals(List.of("Alien"), column("SELECT title FROM " + renamed));

        statement.executeUpdate("DROP VIEW " + renamed);
        assertFalse(tables("VIEW").contains(renamed));
        assertEquals("42S02", assertThrows(SQLException.class,
            () -> statement.executeUpdate("DROP VIEW " + renamed)).getSQLState());
        assertEquals(0, statement.executeUpdate("DROP VIEW IF EXISTS " + renamed));
        assertEquals(List.of("Alien"), column("SELECT title FROM " + films), "the collection stays");
    }

    /**
     * Qdrant deletes a collection's aliases with it; the columns kept under the alias's name go
     * too, so the alias reads as missing rather than as the collection that's gone.
     */
    @Test
    void forgetsAViewWhoseCollectionIsDropped() throws SQLException {
        String view = prefix + "old";
        statement.executeUpdate("CREATE VIEW " + view + " AS SELECT * FROM " + books);
        column("SELECT title FROM " + view);

        statement.executeUpdate("DROP TABLE " + books);

        assertEquals("42S02", assertThrows(SQLException.class,
            () -> column("SELECT title FROM " + view)).getSQLState());
    }

    @Test
    void refusesIndexesAndViewsOnAReadOnlyConnection() throws SQLException {
        connection.setReadOnly(true);

        assertEquals("25006", assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CREATE INDEX ON " + books + " (year)")).getSQLState());
        assertEquals("25006", assertThrows(SQLException.class, () -> statement.executeUpdate(
            "CREATE VIEW " + prefix + "v AS SELECT * FROM " + books)).getSQLState());
    }

    private List<String> column(String sql) throws SQLException {
        var values = new ArrayList<String>();

        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }

        return values;
    }

    private List<String> tables(String type) throws SQLException {
        var names = new ArrayList<String>();

        try (ResultSet rows = connection.getMetaData().getTables(null, null, prefix + "%",
            new String[] {type})) {
            while (rows.next()) names.add(rows.getString("TABLE_NAME"));
        }

        return names;
    }
}
