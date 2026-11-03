package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs LIKE and the full-text conditions against real Qdrant, so the matches the driver sends
 * are ones Qdrant accepts and the points they return are the ones the pattern asked for. The
 * cases worth running against a server rather than asserting on JSON are the ones where Qdrant
 * decides: that a prefix and a substring are case-sensitive, that both reach every element of an
 * array, and that a phrase needs an index built for it. Needs Qdrant:
 * {@code docker compose up -d}.
 */
@Tag("integration")
class TextMatchingIntegrationTest {

    private static final String PREFIX =
        "jdbc_text_" + UUID.randomUUID().toString().substring(0, 8) + "_";

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
     * The sample products: 1 Desk lamp (black, lighting + office), 2 Office chair (grey,
     * furniture + office), 3 Standing desk (white, furniture), 4 Monitor arm (black, office),
     * 5 Cable tray (white, no tags).
     *
     * <p>Qdrant matches a prefix and a substring byte-wise, so 'desk' and 'Desk' find different
     * products: 3 holds "Standing desk" and 1 is "Desk lamp".
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "name LIKE 'Desk%'                  | 1",
        "name LIKE 'Desk lamp'              | 1",
        "name LIKE '%desk%'                 | 3",
        "name LIKE '%Desk%'                 | 1",
        "name LIKE '%r%'                    | 2,4,5",
        "name NOT LIKE '%r%'                | 1,3",
        "name LIKE 'Z%'                     | ",
        "tags LIKE 'off%'                   | 1,2,4",
        "tags LIKE '%ffic%'                 | 1,2,4",
        "specs.color LIKE 'bl%'             | 1,4",
        "name LIKE 'Desk%' OR name LIKE 'Cable%' | 1,5",
        "name LIKE '%desk%' AND price > 400 | 3",
        "created_at LIKE '2024-03%'         | 1"})
    void matchesThePointsThePatternAsksFor(String condition, String ids) throws SQLException {
        assertEquals(expectedIds(ids), ids("SELECT _id FROM " + PREFIX + "products WHERE " + condition));
    }

    /**
     * A field with no payload index at all: Qdrant matches a pattern against it by reading the
     * payload, as it filters an unindexed field for any other condition.
     */
    @Test
    void matchesAPatternOnAFieldWithNoIndex() throws SQLException {
        assertEquals(List.of("1"), ids("SELECT _id FROM " + PREFIX + "events "
            + "WHERE kind LIKE 'sign%'"));
        assertEquals(List.of("2"), ids("SELECT _id FROM " + PREFIX + "events "
            + "WHERE kind LIKE '%urchas%'"));
    }

    /**
     * SQL says a pattern is false for a point that hasn't the field, and so is its negation:
     * product 5 has no tags, so it's in neither answer.
     */
    @Test
    void leavesOutPointsWithoutTheFieldWhetherThePatternIsNegatedOrNot() throws SQLException {
        String from = "SELECT _id FROM " + PREFIX + "products WHERE tags ";

        assertEquals(List.of("1", "2", "4"), ids(from + "LIKE 'off%'"));
        assertEquals(List.of("3"), ids(from + "NOT LIKE 'off%'"));
    }

    @Test
    void matchesAPatternGivenAsAParameter() throws SQLException {
        String sql = "SELECT _id FROM " + PREFIX + "products WHERE name LIKE ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "Desk%");
            assertEquals(List.of("1"), ids(statement));

            statement.setString(1, "%desk%");
            assertEquals(List.of("3"), ids(statement));
        }
    }

    /**
     * The articles: "Refund policy explained" (en), "Rückgaberecht im Überblick" (de), and
     * "Shipping times by region" (en), with a Qdrant text index on the title.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "text_match(title, 'refund')                 | 1",
        "text_match(title, 'refund policy')          | 1",
        "text_match(title, 'policy refund')          | 1",
        "text_match(title, 'refund shipping')        | ",
        "text_any(title, 'refund shipping')          | 1,3",
        "text_any(title, 'refund rückgaberecht')     | 1,2",
        "text_match(title, 'Refund')                 | 1",
        "NOT text_match(title, 'refund')             | 2,3",
        "text_match(title, 'shipping') AND lang = 'en' | 3"})
    void matchesTheWordsOfAnIndexedText(String condition, String articles) throws SQLException {
        assertEquals(expectedTitles(articles),
            titles("SELECT title FROM " + PREFIX + "articles WHERE " + condition));
    }

    /**
     * A full-text condition reaches each element of an array, as an exact match does.
     */
    @Test
    void matchesTheWordsOfAnArrayField() throws SQLException {
        assertEquals(List.of("1", "2", "4"), ids("SELECT _id FROM " + PREFIX + "products "
            + "WHERE text_match(tags, 'office')"));
    }

    /**
     * The case the driver has to check rather than ask: Qdrant answers a phrase match against an
     * index that wasn't built for one with no points at all, which reads exactly like a phrase
     * nothing holds. So the statement fails until a phrase index is built, and the index that
     * builds it is one the driver writes too.
     */
    @Test
    void refusesAPhraseUntilAnIndexCanMatchOneAndThenRunsIt() throws SQLException {
        String articles = PREFIX + "articles";
        String phrase = "SELECT title FROM " + articles
            + " WHERE phrase_match(title, 'refund policy')";

        SQLException error = assertThrows(SQLException.class, () -> ids(phrase));
        assertTrue(error.getMessage().contains("phrase_matching"), error.getMessage());

        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX title ON " + articles);
            statement.execute("CREATE INDEX ON " + articles
                + " (title) USING text WITH (phrase_matching = true)");
        }

        assertEquals(List.of("Refund policy explained"), titles(phrase));

        // The words in the other order are a different phrase, which is the point of one
        assertEquals(List.of(), titles("SELECT title FROM " + articles
            + " WHERE phrase_match(title, 'policy refund')"));
    }

    /**
     * Patterns Qdrant would answer with no points, which the driver fails instead so an empty
     * result never stands for a pattern it couldn't run.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "name LIKE '%lamp'",
        "name LIKE 'D%k'",
        "name LIKE 'Desk_'",
        "name LIKE '%'",
        "price LIKE '39%'",
        "in_stock LIKE 'tr%'",
        "name ILIKE 'desk%'"})
    void refusesPatternsItCantRunRatherThanAnswerWithNoPoints(String condition) {
        assertThrows(SQLException.class,
            () -> ids("SELECT _id FROM " + PREFIX + "products WHERE " + condition));
    }

    /**
     * A pattern bounds a count and a delete as it bounds a read, since all three send the same
     * filter. On a collection of its own, so the counts don't depend on what the other tests
     * left behind.
     */
    @Test
    void countsAndDeletesByAPattern() throws SQLException {
        String table = PREFIX + "patterns";

        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table
                + " (v VECTOR(2) DISTANCE COSINE, name KEYWORD INDEX)");

            try {
                statement.executeUpdate("INSERT INTO " + table + " (_id, name, v) VALUES "
                    + "(1, 'Desk lamp', [0.1, 0.2]), (2, 'Office chair', [0.2, 0.1]), "
                    + "(3, 'Standing desk', [0.3, 0.1])");

                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table
                    + " WHERE name LIKE '%desk%'")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getLong(1));   // Standing desk, not Desk lamp
                }

                // Desk lamp and Standing desk hold a 'k'; Office chair doesn't
                assertEquals(2, statement.executeUpdate("DELETE FROM " + table
                    + " WHERE name LIKE '%k%'"));
                assertEquals(List.of("2"), ids("SELECT _id FROM " + table));
            } finally {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private static List<String> ids(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return values(statement.executeQuery(sql));
        }
    }

    private static List<String> ids(PreparedStatement statement) throws SQLException {
        return values(statement.executeQuery());
    }

    private static List<String> titles(String sql) throws SQLException {
        return sorted(ids(sql));
    }

    private static List<String> values(ResultSet rows) throws SQLException {
        var values = new ArrayList<String>();

        try (rows) {
            while (rows.next()) values.add(rows.getString(1));
        }

        return values;
    }

    /** The expected IDs of a CSV row, where an empty cell means no points. */
    private static List<String> expectedIds(String ids) {
        return ids == null || ids.isBlank() ? List.of() : List.of(ids.split(","));
    }

    /**
     * The expected titles of a CSV row, given as article numbers in the order
     * {@link SampleCollections} loads them, sorted so the comparison doesn't depend on the
     * order Qdrant answers in.
     */
    private static List<String> expectedTitles(String articles) {
        List<String> all = List.of("Refund policy explained", "Rückgaberecht im Überblick",
            "Shipping times by region");

        return sorted(expectedIds(articles).stream().map(x -> all.get(Integer.parseInt(x.strip()) - 1))
            .toList());
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }
}
