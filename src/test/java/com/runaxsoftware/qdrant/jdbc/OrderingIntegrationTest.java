package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Reads the sample points in order through real Qdrant, so the order the driver asks for is one
 * Qdrant runs and the rows come back in it. Qdrant answers an ordered scroll in one page — it
 * returns no next offset for one, and rejects an {@code offset}, which is why an OFFSET here is
 * over-fetched and skipped in the driver — and orders only by a field with a range index, all of
 * which this checks against the server itself. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class OrderingIntegrationTest {

    private static final String PREFIX =
        "jdbc_order_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    private static QdrantGateway gateway;
    private static Connection connection;
    private static String products;

    @BeforeAll
    static void createCollections() throws SQLException {
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, PREFIX);
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
        products = PREFIX + "products";
    }

    @AfterAll
    static void deleteCollections() throws SQLException {
        if (connection != null) {
            connection.close();
        }

        if (gateway != null) {
            SampleCollections.deleteAliases(gateway, PREFIX);
            SampleCollections.delete(gateway, PREFIX);
            gateway.close();
        }
    }

    /**
     * The sample products cost 39.9, 189.0, 449.0, 89.5 and 19, by point ID, and their
     * {@code price} has a float index.
     */
    @Test
    void readsPointsInOrderOfARangeIndexedNumber() throws SQLException {
        assertEquals(List.of("5", "1", "4", "2", "3"),
            read("SELECT _id FROM " + products + " ORDER BY price LIMIT 9"));
        assertEquals(List.of("3", "2", "4", "1", "5"),
            read("SELECT _id FROM " + products + " ORDER BY price DESC LIMIT 9"));
        assertEquals(List.of("5", "1"),
            read("SELECT _id FROM " + products + " ORDER BY price ASC LIMIT 2"));
    }

    @Test
    void readsPointsInOrderOfADateTime() throws SQLException {
        assertEquals(List.of("1", "2"),
            read("SELECT _id FROM " + products + " ORDER BY created_at LIMIT 2"));
        assertEquals(List.of("5", "4"),
            read("SELECT _id FROM " + products + " ORDER BY created_at DESC LIMIT 2"));
    }

    @Test
    void readsPointsInQdrantsOwnOrderForTheIdColumn() throws SQLException {
        // Which needs no limit: this one pages through the cursor like any other scroll
        assertEquals(List.of("1", "2", "3", "4", "5"),
            read("SELECT _id FROM " + products + " ORDER BY _id"));
    }

    @Test
    void filtersAndOrdersAtOnce() throws SQLException {
        assertEquals(List.of("3", "2"), read("SELECT t._id FROM " + products + " t "
            + "WHERE t.price > 50 ORDER BY t.price DESC LIMIT 2"));
    }

    @Test
    void ordersTheRowsOfAnAlias() throws SQLException {
        String view = PREFIX + "current";
        SampleCollections.alias(gateway, view, products);

        try {
            assertEquals(List.of("3", "2"),
                read("SELECT _id FROM " + view + " ORDER BY price DESC LIMIT 2"));
        } finally {
            SampleCollections.deleteAliases(gateway, PREFIX);
        }
    }

    @Test
    void stopsAtTheStatementsMaximumRowsWhenAnOrderedReadGivesNoLimit() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(2);

            try (ResultSet rows = statement.executeQuery(
                "SELECT _id FROM " + products + " ORDER BY price DESC")) {
                assertTrue(rows.next());
                assertEquals("3", rows.getString("_id"), "the most expensive");
                assertTrue(rows.next());
                assertFalse(rows.next());
            }
        }
    }

    /**
     * A limit beyond the collection reads all of it: Qdrant answers an ordered scroll in one
     * page, and the driver asks for the whole limit rather than a page of it, so no row is lost.
     */
    @Test
    void readsEveryPointForALimitBeyondTheCollection() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(2);

            try (ResultSet rows = statement.executeQuery(
                "SELECT _id FROM " + products + " ORDER BY price LIMIT 100")) {
                int read = 0;
                while (rows.next()) read++;

                assertEquals(5, read);
            }
        }
    }

    /**
     * A read Qdrant answers in one call is bounded by the connection's default limit when the
     * statement gives none, and the statement says so.
     */
    @Test
    void boundsAnOrderedReadByTheConnectionsDefaultLimit() throws SQLException {
        try (Connection bounded = connect("2");
             Statement statement = bounded.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT _id FROM " + products + " ORDER BY price DESC")) {
            List<String> ids = new ArrayList<>();
            while (rows.next()) ids.add(rows.getString("_id"));

            assertEquals(List.of("3", "2"), ids, "the default limit, not all five");

            SQLWarning warning = statement.getWarnings();
            assertNotNull(warning, "a read the driver bounded itself warns");
            assertEquals("01000", warning.getSQLState());
            assertTrue(warning.getMessage().contains("defaultLimit"), warning.getMessage());
        }
    }

    @Test
    void asksForALimitWhenTheConnectionHasNoDefault() throws SQLException {
        try (Connection strict = connect("0");
             Statement statement = strict.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.executeQuery(
                "SELECT _id FROM " + products + " ORDER BY price DESC"));

            assertEquals("42000", error.getSQLState());
            assertTrue(error.getMessage().contains("needs a LIMIT"), error.getMessage());
        }
    }

    @Test
    void reportsWhatQdrantWontOrderBy() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // Qdrant orders by a field with a range index; name is indexed as a keyword
            SQLException keyword = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT _id FROM " + products
                    + " ORDER BY name LIMIT 2"));
            assertTrue(keyword.getMessage().contains("range index"), keyword.getMessage());

            // in_stock has no index at all
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT _id FROM "
                + products + " ORDER BY in_stock LIMIT 2"));

            // Qdrant reads point IDs upwards only
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT _id FROM "
                + products + " ORDER BY _id DESC LIMIT 2"));
        }
    }

    /**
     * A second connection with its own default limit.
     */
    private static Connection connect(String defaultLimit) throws SQLException {
        Properties properties = TestQdrant.properties();
        properties.setProperty("defaultLimit", defaultLimit);

        return DriverManager.getConnection(TestQdrant.jdbcUrl(), properties);
    }

    private static List<String> read(String sql) throws SQLException {
        List<String> ids = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                ids.add(rows.getString("_id"));
            }
        }

        return ids;
    }
}
