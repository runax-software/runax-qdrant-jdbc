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
import com.runaxsoftware.qdrant.jdbc.support.Json;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class QdrantPreparedStatementTest {

    private FakeQdrantServer server;
    private Connection connection;

    @BeforeEach
    void open() throws Exception {
        server = FakeQdrantServer.start();
        server.collection("products", unnamedVector(2, "Cosine", Map.of()),
            List.of(point(1, Json.object("name", "Lamp", "price", 39.9))));
        connection = new QdrantDriver().connect(server.jdbcUrl(), new Properties());
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
        server.close();
    }

    @Test
    void runsWithTheValuesGivenForEachRun() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT ? AS a, ? AS b")) {
            statement.setString(1, "x");
            statement.setInt(2, 7);

            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("x", rows.getString("a"));
                assertEquals(7, rows.getLong("b"));
            }

            statement.setString(1, "y");
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("y", rows.getString("a"), "a new value, the other kept");
                assertEquals(7, rows.getLong("b"));
            }
        }
    }

    @Test
    void sendsTheValuesInTheFilter() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM products WHERE name = ? AND price > ?")) {
            statement.setString(1, "Lamp");
            statement.setDouble(2, 10.5);
            statement.executeQuery().close();
        }

        assertEquals(Json.object("must", Json.array(
                Json.object("key", "name", "match", Json.object("value", "Lamp")),
                Json.object("key", "price", "range", Json.object("gt", 10.5)))),
            server.scrollRequests().get(server.scrollRequests().size() - 1).get("filter"));
    }

    @Test
    void keepsAValueAsAValueWhateverItHolds() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM products WHERE name = ?")) {
            statement.setString(1, "x' OR '1' = '1");
            statement.executeQuery().close();
        }

        assertEquals(Json.object("must", Json.array(
                Json.object("key", "name", "match", Json.object("value", "x' OR '1' = '1")))),
            server.scrollRequests().get(server.scrollRequests().size() - 1).get("filter"));
    }

    @Test
    void reportsSyntaxErrorsWhenPrepared() {
        assertThrows(SQLSyntaxErrorException.class, () -> connection.prepareStatement("SELEC ?"));
    }

    @Test
    void reportsAMissingValueAndAnIndexOutOfRange() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT ?, ?")) {
            statement.setString(1, "x");

            assertEquals("07001", assertThrows(SQLException.class, statement::executeQuery).getSQLState());
            assertEquals("07009", assertThrows(SQLException.class, () -> statement.setString(3, "z"))
                .getSQLState());
            assertEquals("07009", assertThrows(SQLException.class, () -> statement.setString(0, "z"))
                .getSQLState());

            statement.setString(2, "y");
            statement.clearParameters();
            assertEquals("07001", assertThrows(SQLException.class, statement::executeQuery).getSQLState());
        }
    }

    @Test
    void refusesOtherSqlAndUnsupportedValues() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT ?")) {
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 1"));
            assertThrows(SQLException.class, () -> statement.execute("SELECT 1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DROP TABLE x"));
            assertThrows(SQLException.class, () -> statement.addBatch("SELECT 1"));
            assertThrows(SQLFeatureNotSupportedException.class, () -> statement.setBytes(1, new byte[1]));
            assertThrows(SQLFeatureNotSupportedException.class, () -> statement.setObject(1, new Object()));
        }
    }

    @Test
    void rejectsComparingWithNull() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM products WHERE name = ?")) {
            statement.setNull(1, Types.VARCHAR);

            SQLException error = assertThrows(SQLException.class, statement::executeQuery);
            assertTrue(error.getMessage().contains("IS NULL"), error.getMessage());
        }
    }

    @Test
    void describesItsParameters() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM products WHERE name = ? AND price IN (?, ?)")) {
            ParameterMetaData parameters = statement.getParameterMetaData();

            assertEquals(3, parameters.getParameterCount());
            assertEquals(Types.OTHER, parameters.getParameterType(1));
            assertEquals(ParameterMetaData.parameterModeIn, parameters.getParameterMode(3));
            assertThrows(SQLException.class, () -> parameters.getParameterType(4));
            assertNull(statement.getMetaData());
        }
    }

    @Test
    void takesTheLimitAndOffsetAsValues() throws SQLException {
        server.collection("items", unnamedVector(2, "Cosine", Map.of()), List.of(
            point(1, Json.object("lang", "en")), point(2, Json.object("lang", "en")),
            point(3, Json.object("lang", "en")), point(4, Json.object("lang", "en"))));

        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM items WHERE lang = ? LIMIT ? OFFSET ?")) {
            statement.setString(1, "en");
            statement.setInt(2, 2);
            statement.setLong(3, 1);
            assertEquals(List.of("2", "3"), ids(statement));

            statement.setInt(2, 1);
            statement.setInt(3, 3);
            assertEquals(List.of("4"), ids(statement), "each run plans with its own values");
        }

        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM items ORDER BY similarity(_vector, ?) DESC LIMIT ? OFFSET ?")) {
            statement.setObject(1, new float[] {0.1f, 0.2f});
            statement.setInt(2, 3);
            statement.setInt(3, 2);
            statement.executeQuery().close();
        }

        JsonNode search = server.queryRequests().get(server.queryRequests().size() - 1);
        assertEquals(3, search.get("limit").asInt());
        assertEquals(2, search.get("offset").asInt(), "a search's offset goes to Qdrant");
    }

    @Test
    void rejectsALimitOrOffsetValueThatIsNotAWholeNumberOfRows() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM products LIMIT ? OFFSET ?")) {
            statement.setInt(2, 0);

            assertEquals("07001", assertThrows(SQLException.class, statement::executeQuery)
                .getSQLState(), "an unbound LIMIT is as missing as any other value");

            for (Object bad : new Object[] {-1, 2.5, "3", null, true}) {
                statement.setObject(1, bad);
                SQLException error = assertThrows(SQLDataException.class,
                    statement::executeQuery, String.valueOf(bad));

                assertEquals("22023", error.getSQLState());
                assertTrue(error.getMessage().startsWith("LIMIT takes a whole number of 0 or more"),
                    error.getMessage());
            }

            statement.setInt(1, 5);
            statement.setInt(2, -3);
            assertEquals("OFFSET takes a whole number of 0 or more, not -3",
                assertThrows(SQLDataException.class, statement::executeQuery).getMessage());
        }
    }

    @Test
    void describesALimitOrOffsetParameterAsAWholeNumber() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT _id FROM products WHERE name = ? LIMIT ? OFFSET ?")) {
            ParameterMetaData parameters = statement.getParameterMetaData();

            assertEquals(3, parameters.getParameterCount());
            assertEquals(Types.OTHER, parameters.getParameterType(1));
            assertEquals(Types.BIGINT, parameters.getParameterType(2));
            assertEquals("BIGINT", parameters.getParameterTypeName(3));
            assertEquals(Long.class.getName(), parameters.getParameterClassName(3));
            assertEquals(ParameterMetaData.parameterNoNulls, parameters.isNullable(2));
            assertEquals(ParameterMetaData.parameterNullableUnknown, parameters.isNullable(1));
            assertNull(statement.getMetaData(), "the columns are still known once it runs");
        }
    }

    @Test
    void runsAsExecuteToo() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT ?")) {
            statement.setBoolean(1, true);

            assertTrue(statement.execute());
            try (ResultSet rows = statement.getResultSet()) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1));
                assertFalse(rows.next());
            }
        }
    }

    private static List<String> ids(PreparedStatement statement) throws SQLException {
        var ids = new ArrayList<String>();

        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) ids.add(rows.getString("_id"));
        }

        return ids;
    }
}
