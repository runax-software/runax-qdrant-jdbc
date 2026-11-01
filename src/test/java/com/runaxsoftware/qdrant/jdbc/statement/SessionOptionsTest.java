package com.runaxsoftware.qdrant.jdbc.statement;

import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.point;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.unnamedVector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.QdrantDriver;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.Request;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class SessionOptionsTest {

    private FakeQdrantServer server;
    private Connection connection;

    @BeforeEach
    void open() throws Exception {
        server = FakeQdrantServer.start().collection("docs", unnamedVector(2, "Cosine", Map.of()),
            List.of(point(1, Json.object("lang", "en"))));
        connection = new QdrantDriver().connect(server.jdbcUrl(), new Properties());
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
        server.close();
    }

    @Test
    void sendsWhatTheConnectionSetWithEveryStatementAfter() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertEquals(0, statement.executeUpdate("SET qdrant.consistency = majority"));
            assertFalse(statement.execute("SET qdrant.timeout = 10"));
        }

        try (Statement other = connection.createStatement()) {
            other.executeQuery("SELECT _id FROM docs").close();
        }

        assertEquals("consistency=majority&timeout=10", lastScroll().query(),
            "a SET is the connection's, so another statement sends it too");
    }

    @Test
    void showsTheOptionsAndResetsThem() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("SET qdrant.ordering = strong");
            statement.executeUpdate("SET qdrant.wait = false");

            Map<String, String> shown = show(statement, "SHOW ALL");
            assertEquals("strong", shown.get("qdrant.ordering"));
            assertEquals("false", shown.get("qdrant.wait"));
            assertNull(shown.get("qdrant.consistency"), "not set: Qdrant's default");
            assertEquals(7, shown.size());

            statement.executeUpdate("RESET qdrant.ordering");
            assertNull(show(statement, "SHOW qdrant.ordering").get("qdrant.ordering"));

            statement.executeUpdate("RESET ALL");
            assertEquals("true", show(statement, "SHOW qdrant.wait").get("qdrant.wait"));
        }
    }

    @Test
    void runsOnAReadOnlyConnection() throws SQLException {
        connection.setReadOnly(true);

        try (Statement statement = connection.createStatement()) {
            assertEquals(0, statement.executeUpdate("SET qdrant.consistency = all"));
        }
    }

    @Test
    void keepsSetAndShowToTheirOwnMethods() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeQuery("SET qdrant.timeout = 5"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("SHOW ALL"));
            assertTrue(statement.execute("SHOW ALL"));
        }
    }

    @Test
    void keepsEachConnectionsOptionsItsOwn() throws SQLException {
        try (Connection other = new QdrantDriver().connect(server.jdbcUrl(), new Properties());
             Statement statement = connection.createStatement();
             Statement otherStatement = other.createStatement()) {
            statement.executeUpdate("SET qdrant.consistency = all");

            assertNull(show(otherStatement, "SHOW qdrant.consistency").get("qdrant.consistency"));
        }
    }

    private static Map<String, String> show(Statement statement, String sql) throws SQLException {
        var shown = new LinkedHashMap<String, String>();

        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) shown.put(rows.getString("name"), rows.getString("setting"));
        }

        return shown;
    }

    private Request lastScroll() {
        List<Request> requests = server.requests();

        for (int i = requests.size() - 1; i >= 0; i--) {
            if (requests.get(i).path().endsWith("/points/scroll")) return requests.get(i);
        }

        throw new AssertionError("No scroll");
    }
}
