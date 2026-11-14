package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
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
import java.util.UUID;

/**
 * Makes each kind of change an UPDATE can make on its own, so each goes to the route Qdrant has
 * for that one kind, and reads back what a real server did with it. A mixed UPDATE, which stays
 * one batch, ends the class. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class PointRoutesIntegrationTest {

    private String docs;
    private QdrantGateway gateway;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void createDocs() throws SQLException {
        docs = "jdbc_points_" + UUID.randomUUID().toString().substring(0, 8);
        gateway = TestQdrant.gateway();
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
        statement = connection.createStatement();

        statement.executeUpdate("CREATE TABLE " + docs + " (dense VECTOR(2), extra VECTOR(2))");
        statement.executeUpdate("INSERT INTO " + docs + " (_id, title, lang, dense, extra) VALUES "
            + "(1, 'Dune', 'en', [1, 0], [0, 1]), (2, 'Emma', 'en', [0, 1], [1, 0])");
    }

    @AfterEach
    void deleteDocs() throws SQLException {
        statement.close();
        connection.close();
        gateway.deleteCollection(docs);
        gateway.close();
    }

    @Test
    void setsPayloadFieldsAndKeepsTheOthers() throws SQLException {
        assertEquals(1, statement.executeUpdate(
            "UPDATE " + docs + " SET title = 'Dune Messiah', year = 1969 WHERE _id = 1"));

        assertEquals("{\"title\":\"Dune Messiah\",\"lang\":\"en\",\"year\":1969}", payload(1));
        assertEquals("{\"title\":\"Emma\",\"lang\":\"en\"}", payload(2));
    }

    @Test
    void overwritesTheWholePayload() throws SQLException {
        assertEquals(1, statement.executeUpdate(
            "UPDATE " + docs + " SET _payload = '{\"title\": \"Persuasion\"}' WHERE _id = 2"));

        assertEquals("{\"title\":\"Persuasion\"}", payload(2));
    }

    @Test
    void deletesPayloadFields() throws SQLException {
        assertEquals(2, statement.executeUpdate(
            "UPDATE " + docs + " SET lang = NULL WHERE lang = 'en'"));

        assertEquals("{\"title\":\"Dune\"}", payload(1));
        assertEquals("{\"title\":\"Emma\"}", payload(2));
    }

    @Test
    void clearsTheWholePayload() throws SQLException {
        assertEquals(1, statement.executeUpdate(
            "UPDATE " + docs + " SET _payload = NULL WHERE _id = 1"));

        assertEquals("{}", payload(1));
        assertEquals("{\"title\":\"Emma\",\"lang\":\"en\"}", payload(2));
    }

    @Test
    void writesAVector() throws SQLException {
        assertEquals(1, statement.executeUpdate(
            "UPDATE " + docs + " SET dense = [0.6, 0.8] WHERE _id = 1"));

        try (ResultSet rows = statement.executeQuery(
            "SELECT dense, extra FROM " + docs + " WHERE _id = 1")) {
            assertTrue(rows.next());
            assertEquals("[0.6,0.8]", rows.getString("dense"));
            assertEquals("[0.0,1.0]", rows.getString("extra"), "the other vector is untouched");
        }
    }

    @Test
    void deletesAVector() throws SQLException {
        assertEquals(1, statement.executeUpdate(
            "UPDATE " + docs + " SET extra = NULL WHERE _id = 2"));

        try (ResultSet rows = statement.executeQuery(
            "SELECT dense, extra FROM " + docs + " WHERE _id = 2")) {
            assertTrue(rows.next());
            assertNull(rows.getString("extra"));
            assertEquals("[0.0,1.0]", rows.getString("dense"));
        }
    }

    @Test
    void makesSeveralKindsOfChangeTogether() throws SQLException {
        assertEquals(1, statement.executeUpdate("UPDATE " + docs
            + " SET title = 'Dune II', lang = NULL, dense = [0.6, 0.8], extra = NULL WHERE _id = 1"));

        assertEquals("{\"title\":\"Dune II\"}", payload(1));

        try (ResultSet rows = statement.executeQuery(
            "SELECT dense, extra FROM " + docs + " WHERE _id = 1")) {
            assertTrue(rows.next());
            assertEquals("[0.6,0.8]", rows.getString("dense"));
            assertNull(rows.getString("extra"));
            assertFalse(rows.next());
        }
    }

    private String payload(int id) throws SQLException {
        try (ResultSet rows = statement.executeQuery(
            "SELECT _payload FROM " + docs + " WHERE _id = " + id)) {
            assertTrue(rows.next());

            return rows.getString("_payload");
        }
    }
}
