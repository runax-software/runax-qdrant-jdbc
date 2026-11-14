package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs WHERE clauses against real Qdrant, so the filters the driver sends are filters Qdrant
 * accepts and the points they match are the ones SQL would. Needs Qdrant:
 * {@code docker compose up -d}.
 */
@Tag("integration")
class FilterIntegrationTest {

    private static final String PREFIX =
        "jdbc_filter_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    private static QdrantGateway gateway;
    private static Connection connection;

    @BeforeAll
    static void createCollections() throws SQLException {
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, PREFIX);
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
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
     * The sample products: 1 Desk lamp (39.9, black, office), 2 Office chair (189, grey,
     * furniture), 3 Standing desk (449, white, out of stock), 4 Monitor arm (89.5, black),
     * 5 Cable tray (19, white, no tags).
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "name = 'Desk lamp'                                 | 1",
        "name IN ('Desk lamp', 'Cable tray')                | 1,5",
        "name <> 'Desk lamp'                                | 2,3,4,5",
        "price > 50                                         | 2,3,4",
        "price <= 39.9                                      | 1,5",
        "price = 19                                         | 5",
        "NOT (price > 50)                                   | 1,5",
        "price BETWEEN 19 AND 89.5                          | 1,4,5",
        "price NOT BETWEEN 19 AND 89.5                      | 2,3",
        "price BETWEEN 50 AND 500 AND specs.color = 'black' | 4",
        "created_at BETWEEN '2024-04-01T00:00:00Z' AND '2024-07-02T09:00:00Z' | 2,3,4",
        "in_stock = false                                   | 3",
        "specs.color = 'black'                              | 1,4",
        "\"specs.color\" NOT IN ('black', 'white')          | 2",
        "tags = 'furniture'                                 | 2,3",
        "tags IS NULL                                       | 5",
        "created_at >= '2024-06-01T00:00:00Z'               | 3,4,5",
        "_id IN (2, 4)                                      | 2,4",
        "_id <> 1                                           | 2,3,4,5",
        "price > 50 AND specs.color = 'black'               | 4",
        "price < 20 OR name = 'Office chair'                | 2,5",
        "in_stock = true AND (price < 20 OR price > 100)    | 2,5"})
    void matchesThePointsSqlWould(String condition, String ids) throws SQLException {
        assertEquals(List.of(ids.split(",")),
            ids("SELECT _id FROM " + PREFIX + "products WHERE " + condition));
    }

    @Test
    void matchesNamedVectorsUuidsAndIntegers() throws SQLException {
        assertEquals(List.of("5c56c793-69f3-4fbf-87e6-c4bf54c28c26",
                "e7c1f0d4-3b2a-4f6e-8a9d-1c5b7e3f2a60"),
            sorted(ids("SELECT _id FROM " + PREFIX + "articles WHERE lang = 'en' AND year >= 2024")));
        assertEquals(List.of("0b3e9a2c-7a51-4c6e-9d0f-2f4a6c8e1b37"),
            ids("SELECT _id FROM " + PREFIX + "articles "
                + "WHERE _id = '0b3e9a2c-7a51-4c6e-9d0f-2f4a6c8e1b37'"));
        assertEquals(3, ids("SELECT _id FROM " + PREFIX + "articles WHERE dense IS NOT NULL")
            .size());
    }

    /**
     * The sample events: 1 has a count of 3, 2 of 2.5, the largest ID of 1, and the UUID point
     * has no count at all, so NOT BETWEEN, as SQL says, leaves it out.
     */
    @Test
    void excludesARangeOnlyFromPointsThatHaveTheField() throws SQLException {
        assertEquals(List.of("1", "2"),
            ids("SELECT _id FROM " + PREFIX + "events WHERE count BETWEEN 2 AND 3"));
        assertEquals(List.of(SampleCollections.LARGEST_ID.toString()),
            ids("SELECT _id FROM " + PREFIX + "events WHERE count NOT BETWEEN 2 AND 3"));
    }

    @Test
    void pagesThroughTheMatchingPoints() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(1);

            assertEquals(List.of("2", "3", "4"), ids(statement,
                "SELECT _id FROM " + PREFIX + "products WHERE price > 50"));
        }
    }

    @Test
    void filtersUnderATableAliasAndThroughAQdrantAlias() throws SQLException {
        String view = PREFIX + "current";
        SampleCollections.alias(gateway, view, PREFIX + "products");

        assertEquals(List.of("3"), ids("SELECT t._id FROM collections." + PREFIX + "products t "
            + "WHERE t.price > 400"));
        assertEquals(List.of("3"), ids("SELECT _id FROM " + view + " WHERE price > 400"));
    }

    /**
     * Point 3 is the closest to the vector searched with, but it's out of stock.
     */
    @Test
    void filtersASearch() throws SQLException {
        assertEquals("3", ids("SELECT _id FROM " + PREFIX + "products "
            + "ORDER BY similarity(_vector, [0.1, 0.7, 0.6, 0.2]) DESC LIMIT 1").get(0));

        List<String> inStock = ids("SELECT _id FROM " + PREFIX + "products "
            + "WHERE in_stock = true ORDER BY similarity(_vector, [0.1, 0.7, 0.6, 0.2]) DESC "
            + "LIMIT 5");
        assertEquals(4, inStock.size());
        assertEquals(false, inStock.contains("3"));
    }

    @Test
    void readsOnePointByItsIdOrNoRowWhenThereIsNone() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT _id, name, price, _vector FROM "
                 + PREFIX + "products WHERE _id = 1")) {
            assertTrue(rows.next());
            assertEquals("1", rows.getString("_id"));
            assertEquals("Desk lamp", rows.getString("name"));
            assertEquals(39.9, rows.getDouble("price"));
            assertTrue(rows.getString("_vector").startsWith("[0.9"), rows.getString("_vector"));
            assertFalse(rows.next());
        }

        assertEquals(List.of(), ids("SELECT _id FROM " + PREFIX + "products WHERE _id = 987654"));
        assertEquals(List.of("1"), ids("SELECT _id FROM " + PREFIX + "products WHERE _id = '1'"));
        assertEquals(List.of(), ids("SELECT _id FROM " + PREFIX
            + "products WHERE _id = 1 OFFSET 1"));
        assertEquals("42S02", assertThrows(SQLException.class, () -> ids("SELECT _id FROM "
            + PREFIX + "gone WHERE _id = 1")).getSQLState());
    }

    private static List<String> ids(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return ids(statement, sql);
        }
    }

    private static List<String> ids(Statement statement, String sql) throws SQLException {
        var ids = new ArrayList<String>();

        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getString(1));
        }

        return ids;
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }
}
