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
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Counts the sample points per group through real Qdrant, so the counts are Qdrant's own.
 * Qdrant counts the values of a field with a keyword, integer, bool, or UUID index, answers the
 * most common first, and counts the ten most common when a request gives no limit — all of
 * which this checks against the server itself. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class GroupingIntegrationTest {

    private static final String PREFIX =
        "jdbc_group_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    private static QdrantGateway gateway;
    private static Connection connection;
    private static String products;
    private static String articles;

    @BeforeAll
    static void createCollections() throws SQLException {
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, PREFIX);
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
        products = PREFIX + "products";
        articles = PREFIX + "articles";
    }

    @AfterAll
    static void deleteCollections() throws SQLException {
        if (connection != null) {
            connection.close();
        }

        if (gateway != null) {
            SampleCollections.delete(gateway, PREFIX);
            gateway.close();
        }
    }

    /**
     * The sample products are tagged lighting+office, furniture+office, furniture, office, and
     * office, so a point counts once per tag.
     */
    @Test
    void countsThePointsPerValueOfAnArrayField() throws SQLException {
        assertEquals(List.of("office=3", "furniture=2", "lighting=1"),
            read("SELECT tags, COUNT(*) FROM " + products + " GROUP BY tags LIMIT 9"));
    }

    @Test
    void countsThePointsPerValueOfAKeywordField() throws SQLException {
        assertEquals(List.of("black=2", "white=2", "grey=1"),
            read("SELECT specs.color, COUNT(*) FROM " + products
                + " GROUP BY specs.color LIMIT 9"));
        assertEquals(List.of("en=2", "de=1"),
            read("SELECT lang, COUNT(*) FROM " + articles + " GROUP BY lang LIMIT 9"));
    }

    @Test
    void countsThePointsPerWholeNumber() throws SQLException {
        assertEquals(List.of("2023=1", "2024=1", "2025=1"),
            read("SELECT year, COUNT(*) FROM " + articles + " GROUP BY year LIMIT 9"));
    }

    @Test
    void countsOnlyThePointsAFilterMatches() throws SQLException {
        // The standing desk is the one out of stock, and it's the second furniture point
        assertEquals(List.of("office=3", "furniture=1", "lighting=1"),
            read("SELECT tags, COUNT(*) FROM " + products + " WHERE in_stock = true "
                + "GROUP BY tags LIMIT 9"));
    }

    @Test
    void readsTheGroupsUnderTheNamesTheStatementGives() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) AS n, t.tags AS tag FROM "
                 + products + " t GROUP BY t.tags LIMIT 1")) {
            assertEquals("n", rows.getMetaData().getColumnLabel(1));
            assertEquals(Types.BIGINT, rows.getMetaData().getColumnType(1));
            assertEquals("tag", rows.getMetaData().getColumnLabel(2));
            assertEquals(Types.VARCHAR, rows.getMetaData().getColumnType(2));

            assertTrue(rows.next());
            assertEquals(3L, rows.getLong("n"));
            assertEquals("office", rows.getString("tag"));
            assertFalse(rows.next(), "the limit");
        }
    }

    /**
     * A GROUP BY without a count reads the values themselves, which is what Qdrant's facet
     * answers either way.
     */
    @Test
    void readsTheValuesOfAFieldWithoutCountingThem() throws SQLException {
        assertEquals(List.of("office", "furniture", "lighting"),
            read("SELECT tags FROM " + products + " GROUP BY tags LIMIT 9"));
    }

    @Test
    void stopsAtTheStatementsMaximumRowsWhenAGroupedStatementGivesNoLimit() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(2);

            try (ResultSet rows = statement.executeQuery(
                "SELECT tags, COUNT(*) FROM " + products + " GROUP BY tags")) {
                assertTrue(rows.next());
                assertEquals("office", rows.getString("tags"));
                assertTrue(rows.next());
                assertFalse(rows.next());
            }
        }
    }

    /**
     * A grouped statement that gives no limit reads the connection's default, and says so.
     */
    @Test
    void boundsAGroupedStatementByTheConnectionsDefaultLimit() throws SQLException {
        Properties properties = TestQdrant.properties();
        properties.setProperty("defaultLimit", "2");

        try (Connection bounded = DriverManager.getConnection(TestQdrant.jdbcUrl(), properties);
             Statement statement = bounded.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT tags, COUNT(*) FROM " + products + " GROUP BY tags")) {
            int read = 0;
            while (rows.next()) read++;

            assertEquals(2, read, "the default limit, not all three tags");
            assertNotNull(statement.getWarnings());
        }
    }

    @Test
    void reportsWhatQdrantWontGroupBy() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // price is indexed as a float, which Qdrant can't count the values of
            SQLException number = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT price, COUNT(*) FROM " + products
                    + " GROUP BY price LIMIT 5"));
            assertTrue(number.getMessage().contains("indexed as float"), number.getMessage());

            // in_stock has no index at all
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT in_stock, "
                + "COUNT(*) FROM " + products + " GROUP BY in_stock LIMIT 5"));

            // A column that isn't the grouped one
            assertEquals("42S22", assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT name, COUNT(*) FROM " + products
                    + " GROUP BY tags LIMIT 5")).getSQLState());

            // And more than one field, which Qdrant counts one at a time
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT COUNT(*) FROM "
                + products + " GROUP BY tags, name LIMIT 5"));
        }
    }

    /**
     * Each row as {@code value=count}, or the single column's value when there's one column.
     */
    private static List<String> read(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet answered = statement.executeQuery(sql)) {
            int columns = answered.getMetaData().getColumnCount();

            while (answered.next()) {
                rows.add(columns == 1 ? answered.getString(1)
                    : answered.getString(1) + "=" + answered.getString(2));
            }
        }

        return rows;
    }
}
