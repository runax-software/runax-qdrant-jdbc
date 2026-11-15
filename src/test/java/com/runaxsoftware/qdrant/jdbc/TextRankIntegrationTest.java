package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ranks a real collection's points by full-text relevance, with {@code text_rank}: Qdrant's
 * text query, BM25 over a text index built to score. Needs Qdrant: {@code docker compose up -d}.
 *
 * <p>The articles, best match for "refund policy" first: 1 "refund policy for orders" (en),
 * 3 "refund refund refund" (de), 2 "shipping policy" (en). Article 4, "unrelated" (en), holds
 * neither word, so no search for them returns it.
 */
@Tag("integration")
class TextRankIntegrationTest {

    private String articles;
    private QdrantGateway gateway;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void createArticles() throws SQLException {
        articles = "jdbc_text_rank_" + UUID.randomUUID().toString().substring(0, 8);
        gateway = TestQdrant.gateway();
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
        statement = connection.createStatement();

        statement.executeUpdate("CREATE TABLE " + articles + " (dense VECTOR(2))");
        statement.executeUpdate("INSERT INTO " + articles + " (_id, title, body, lang, dense) "
            + "VALUES (1, 'refund policy for orders', 'refund', 'en', [1, 0]), "
            + "(2, 'shipping policy', 'shipping', 'en', [0, 1]), "
            + "(3, 'refund refund refund', 'refund', 'de', [1, 1]), "
            + "(4, 'unrelated', 'nothing', 'en', [1, 1])");
        statement.executeUpdate("CREATE INDEX ON " + articles
            + " (title) USING text WITH (scoring = true)");
        statement.executeUpdate("CREATE INDEX ON " + articles + " (body) USING text");
    }

    @AfterEach
    void deleteArticles() throws SQLException {
        statement.close();
        connection.close();
        gateway.deleteCollection(articles);
        gateway.close();
    }

    @Test
    void ranksTheArticlesThatHoldAnyOfTheWordsBestFirst() throws SQLException {
        List<Double> scores = new ArrayList<>();
        List<String> ids = new ArrayList<>();

        try (ResultSet rows = statement.executeQuery("SELECT _id, _score, title FROM " + articles
            + " ORDER BY text_rank(title, 'refund policy') DESC LIMIT 10")) {
            while (rows.next()) {
                ids.add(rows.getString("_id"));
                scores.add(rows.getDouble("_score"));
            }
        }

        assertEquals(List.of("1", "3", "2"), ids, "4 holds neither word");
        assertTrue(scores.get(0) > scores.get(1) && scores.get(1) > scores.get(2)
            && scores.get(2) > 0, scores.toString());
    }

    @Test
    void filtersAndSkipsAsAnyOtherSearchDoes() throws SQLException {
        assertEquals(List.of("1", "2"), ids("SELECT _id FROM " + articles
            + " WHERE lang = 'en' ORDER BY text_rank(title, 'refund policy') DESC LIMIT 10"));
        assertEquals(List.of("3", "2"), ids("SELECT _id FROM " + articles
            + " ORDER BY text_rank(title, 'refund policy') DESC LIMIT 10 OFFSET 1"));
        assertEquals(List.of("1"), ids("SELECT _id FROM " + articles
            + " ORDER BY text_rank(title, 'refund policy') DESC LIMIT 1"));
    }

    @Test
    void keepsTheBestArticleOfEachLanguage() throws SQLException {
        assertEquals(List.of("1", "3"), ids("SELECT _id FROM " + articles
            + " ORDER BY text_rank(title, 'refund policy') DESC LIMIT 1 BY lang LIMIT 10"));
    }

    @Test
    void takesTheWordsAsAParameter() throws SQLException {
        try (PreparedStatement prepared = connection.prepareStatement("SELECT _id FROM "
            + articles + " ORDER BY text_rank(title, ?) DESC LIMIT 10")) {
            prepared.setString(1, "shipping");

            try (ResultSet rows = prepared.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("2", rows.getString(1));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void runsSeveralSearchesByTextInOneCall() throws SQLException {
        assertTrue(statement.execute("SELECT _id FROM " + articles
            + " ORDER BY text_rank(title, 'shipping') DESC LIMIT 1; SELECT _id FROM " + articles
            + " ORDER BY similarity(dense, [0, 1]) DESC LIMIT 1"));

        try (ResultSet rows = statement.getResultSet()) {
            assertTrue(rows.next());
            assertEquals("2", rows.getString(1));
        }

        assertTrue(statement.getMoreResults());

        try (ResultSet rows = statement.getResultSet()) {
            assertTrue(rows.next());
            assertEquals("2", rows.getString(1));
        }
    }

    @Test
    void saysHowToBuildAnIndexThatScoresWhenTheFieldsDoesnt() {
        SQLException error = assertThrows(SQLException.class, () -> ids("SELECT _id FROM "
            + articles + " ORDER BY text_rank(body, 'refund') DESC LIMIT 10"));

        assertEquals("The text index on 'body' of " + articles + " wasn't built to score, so "
            + "Qdrant can't rank by it. Replace it: DROP INDEX ON " + articles + " (body), then "
            + "CREATE INDEX ON " + articles + " (body) USING text WITH (scoring = true)",
            error.getMessage());
    }

    @Test
    void replacesAnIndexWithOneThatScoresAsTheErrorSays() throws SQLException {
        statement.executeUpdate("DROP INDEX ON " + articles + " (body)");
        statement.executeUpdate("CREATE INDEX ON " + articles
            + " (body) USING text WITH (scoring = true)");

        assertEquals(List.of("2"), ids("SELECT _id FROM " + articles
            + " ORDER BY text_rank(body, 'shipping') DESC LIMIT 10"));
    }

    @Test
    void needsATextIndexBeforeItAsksQdrant() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> ids("SELECT _id FROM "
            + articles + " ORDER BY text_rank(lang, 'en') DESC LIMIT 10"));
    }

    private List<String> ids(String sql) throws SQLException {
        List<String> ids = new ArrayList<>();

        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getString("_id"));
        }

        return ids;
    }
}
