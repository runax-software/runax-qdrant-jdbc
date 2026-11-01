package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.sql.planner.Planner;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pages past the first rows of every kind of read through real Qdrant, which is what a data grid
 * asks for. Each kind reaches its OFFSET a different way, and the differences are Qdrant's: a
 * plain scroll's {@code offset} is the point ID to start at rather than a row count, an ordered
 * scroll rejects an {@code offset} outright and answers in one page, the Query API's
 * {@code offset} is a real row count, and the facet API has no offset field at all and ignores
 * one. So each read here is checked against the same read without an offset: the offset rows are
 * the ones the unoffset read shows next, whoever did the skipping. Needs Qdrant:
 * {@code docker compose up -d}.
 */
@Tag("integration")
class OffsetIntegrationTest {

    private static final String PREFIX =
        "jdbc_offset_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    // The vector the searches rank by, which is the first sample article's dense vector
    private static final String SEARCH = "similarity(dense, [0.1, 0.2, 0.3, 0.4]) DESC";

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
     * A plain read, which the driver pages past with the scroll cursor: Qdrant's {@code offset}
     * names a point ID, so the skipped points are read and discarded.
     */
    @Test
    void pagesPastTheFirstPointsOfAPlainRead() throws SQLException {
        List<String> all = read("SELECT _id FROM " + products);
        assertEquals(List.of("1", "2", "3", "4", "5"), all, "the sample products, by point ID");

        assertEquals(all.subList(2, 4), read("SELECT _id FROM " + products + " LIMIT 2 OFFSET 2"));
        assertEquals(all.subList(3, 5), read("SELECT _id FROM " + products + " OFFSET 3"));
        assertEquals(all.subList(1, 5), read("SELECT _id FROM " + products + " LIMIT 9 OFFSET 1"));
    }

    @Test
    void returnsNoRowsWhenAPlainReadsOffsetPassesTheLastPoint() throws SQLException {
        assertEquals(List.of(), read("SELECT _id FROM " + products + " OFFSET 5"));
        assertEquals(List.of(), read("SELECT _id FROM " + products + " LIMIT 2 OFFSET 99"));
    }

    /**
     * A filtered read skips the points the filter matched, not the points Qdrant looked at: the
     * three products over 50 are the standing desk, the office chair, and the monitor arm.
     */
    @Test
    void skipsTheMatchedPointsOfAFilteredRead() throws SQLException {
        List<String> all = read("SELECT _id FROM " + products + " WHERE price > 50");
        assertEquals(List.of("2", "3", "4"), all);

        assertEquals(all.subList(1, 3),
            read("SELECT _id FROM " + products + " WHERE price > 50 OFFSET 1"));
    }

    /**
     * An ordered read, where Qdrant rejects an {@code offset} beside an {@code order_by} and
     * answers in a single page: the driver asks for the limit and the skip together and drops the
     * first points. The sample products cost 19, 39.9, 89.5, 189 and 449, by point 5, 1, 4, 2, 3.
     */
    @Test
    void pagesPastTheFirstRowsOfAnOrderedRead() throws SQLException {
        List<String> all = read("SELECT _id FROM " + products + " ORDER BY price LIMIT 9");
        assertEquals(List.of("5", "1", "4", "2", "3"), all);

        assertEquals(all.subList(1, 3),
            read("SELECT _id FROM " + products + " ORDER BY price LIMIT 2 OFFSET 1"));
        assertEquals(all.subList(3, 5),
            read("SELECT _id FROM " + products + " ORDER BY price LIMIT 9 OFFSET 3"));

        List<String> down = read("SELECT _id FROM " + products + " ORDER BY price DESC LIMIT 9");
        assertEquals(down.subList(3, 5),
            read("SELECT _id FROM " + products + " ORDER BY price DESC LIMIT 2 OFFSET 3"));
    }

    @Test
    void returnsNoRowsWhenAnOrderedReadsOffsetPassesTheLastPoint() throws SQLException {
        assertEquals(List.of(),
            read("SELECT _id FROM " + products + " ORDER BY price LIMIT 2 OFFSET 5"));
        assertEquals(List.of(),
            read("SELECT _id FROM " + products + " ORDER BY price LIMIT 9 OFFSET 99"));
    }

    /**
     * A search, whose OFFSET is the one Qdrant applies itself: its Query API counts rows the way
     * SQL does, so the limit still counts the points returned after the offset.
     */
    @Test
    void pagesPastTheClosestPointsOfASearch() throws SQLException {
        List<String> all = read("SELECT _id FROM " + articles + " ORDER BY " + SEARCH + " LIMIT 9");
        assertEquals(3, all.size(), "the sample articles, ranked");

        assertEquals(all.subList(1, 3),
            read("SELECT _id FROM " + articles + " ORDER BY " + SEARCH + " LIMIT 2 OFFSET 1"));
        assertEquals(all.subList(2, 3),
            read("SELECT _id FROM " + articles + " ORDER BY " + SEARCH + " LIMIT 9 OFFSET 2"));
    }

    @Test
    void returnsNoRowsWhenASearchsOffsetPassesTheLastPoint() throws SQLException {
        assertEquals(List.of(),
            read("SELECT _id FROM " + articles + " ORDER BY " + SEARCH + " LIMIT 2 OFFSET 3"));
        assertEquals(List.of(),
            read("SELECT _id FROM " + articles + " ORDER BY " + SEARCH + " LIMIT 9 OFFSET 99"));
    }

    /**
     * The scores stay the scores of the points, since Qdrant ranks them all and then passes over
     * the first: an offset page isn't rescored from 1.0 down again.
     */
    @Test
    void keepsTheScoresOfTheRankedPointsThroughAnOffset() throws SQLException {
        List<String> all = read("SELECT _score FROM " + articles + " ORDER BY " + SEARCH
            + " LIMIT 9");

        assertEquals(all.subList(1, 3), read("SELECT _score FROM " + articles + " ORDER BY "
            + SEARCH + " LIMIT 9 OFFSET 1"));
    }

    /**
     * A grouped read, whose OFFSET the driver skips because Qdrant's facet API has no offset
     * field — and ignores one rather than reporting it, so sending the statement's offset would
     * quietly answer with the groups it asked to pass over. The sample products are tagged
     * office three times, furniture twice, and lighting once.
     */
    @Test
    void pagesPastTheMostCommonGroups() throws SQLException {
        List<String> all = read("SELECT tags, COUNT(*) FROM " + products
            + " GROUP BY tags LIMIT 9");
        assertEquals(List.of("office=3", "furniture=2", "lighting=1"), all);

        assertEquals(all.subList(1, 3),
            read("SELECT tags, COUNT(*) FROM " + products + " GROUP BY tags LIMIT 9 OFFSET 1"));
        assertEquals(all.subList(1, 2),
            read("SELECT tags, COUNT(*) FROM " + products + " GROUP BY tags LIMIT 1 OFFSET 1"));
    }

    @Test
    void returnsNoGroupsWhenAnOffsetPassesTheLastOne() throws SQLException {
        assertEquals(List.of(),
            read("SELECT tags, COUNT(*) FROM " + products + " GROUP BY tags LIMIT 9 OFFSET 3"));
        assertEquals(List.of(),
            read("SELECT tags, COUNT(*) FROM " + products + " GROUP BY tags LIMIT 2 OFFSET 99"));
    }

    /**
     * A system table, which is read into memory whatever the statement asks for, so its OFFSET
     * just starts further along the rows it already has.
     */
    @Test
    void pagesPastTheFirstRowsOfASystemTable() throws SQLException {
        List<String> all = read("SELECT name FROM system.collections");
        assertTrue(all.size() >= 3, "the three sample collections at least: " + all);

        assertEquals(all.subList(1, all.size()),
            read("SELECT name FROM system.collections OFFSET 1"));
        assertEquals(all.subList(2, Math.min(4, all.size())),
            read("SELECT name FROM system.collections LIMIT 2 OFFSET 2"));
        assertEquals(List.of(),
            read("SELECT name FROM system.collections OFFSET " + all.size()));
    }

    /**
     * A count is one row, so any OFFSET is already past it: no row, and nothing counted, the way
     * a LIMIT of no rows leaves it out.
     */
    @Test
    void skipsTheSingleRowOfACount() throws SQLException {
        assertEquals(List.of("5"), read("SELECT COUNT(*) FROM " + products));
        assertEquals(List.of("5"), read("SELECT COUNT(*) FROM " + products + " OFFSET 0"));
        assertEquals(List.of(), read("SELECT COUNT(*) FROM " + products + " OFFSET 1"));
    }

    /**
     * The maximum rows bound the rows the result set yields, as JDBC says, so they count the rows
     * after the skip rather than against it.
     */
    @Test
    void countsTheMaximumRowsAfterTheSkip() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(2);

            try (ResultSet rows = statement.executeQuery(
                "SELECT _id FROM " + products + " OFFSET 2")) {
                assertTrue(rows.next());
                assertEquals("3", rows.getString("_id"));
                assertTrue(rows.next());
                assertEquals("4", rows.getString("_id"));
                assertFalse(rows.next(), "the maximum rows, counted after the two skipped");
            }
        }
    }

    /**
     * The fetch size still sets the page a plain read is skipped through, so a skip deeper than
     * one page pages to the row the OFFSET names rather than asking for it all at once.
     */
    @Test
    void skipsThroughPagesOfTheFetchSize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(2);

            try (ResultSet rows = statement.executeQuery(
                "SELECT _id FROM " + products + " LIMIT 1 OFFSET 4")) {
                assertTrue(rows.next());
                assertEquals("5", rows.getString("_id"), "the last point, three pages in");
                assertFalse(rows.next());
            }
        }
    }

    /**
     * A one-call read whose limit came from the connection's {@code defaultLimit} rather than the
     * SQL still pages: the over-fetch is that limit plus the offset, and because a limit bounds
     * the rows returned the warning stays true of what the statement actually sees.
     */
    @Test
    void pagesAReadBoundedByTheConnectionsDefaultLimit() throws SQLException {
        List<String> all = read("SELECT _id FROM " + products + " ORDER BY price LIMIT 9");

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT _id FROM " + products + " ORDER BY price OFFSET 2")) {
            var ids = new ArrayList<String>();
            while (rows.next()) ids.add(rows.getString("_id"));

            assertEquals(all.subList(2, 5), ids, "the tail of the ordered read");
            assertTrue(statement.getWarnings().getMessage().contains("defaultLimit"),
                statement.getWarnings().getMessage());
        }

        assertEquals(List.of("furniture=2", "lighting=1"),
            read("SELECT tags, COUNT(*) FROM " + products + " GROUP BY tags OFFSET 1"));
    }

    /**
     * With no default to fall back on, an OFFSET doesn't stand in for the limit a one-call read
     * needs — but a plain read never needed one, so it pages on an OFFSET alone.
     */
    @Test
    void stillAsksForALimitWhenTheConnectionHasNoDefault() throws SQLException {
        try (Connection unbounded = DriverManager.getConnection(
            TestQdrant.jdbcUrl() + "?defaultLimit=0", TestQdrant.properties());
             Statement statement = unbounded.createStatement()) {

            for (String sql : List.of(
                "SELECT _id FROM " + products + " ORDER BY price OFFSET 2",
                "SELECT tags, COUNT(*) FROM " + products + " GROUP BY tags OFFSET 1",
                "SELECT _id FROM " + articles + " ORDER BY " + SEARCH + " OFFSET 1")) {
                SQLException error = assertThrows(SQLException.class,
                    () -> statement.executeQuery(sql), sql);

                assertEquals("42000", error.getSQLState(), sql);
                assertTrue(error.getMessage().contains("needs a LIMIT"), error.getMessage());
            }

            try (ResultSet rows = statement.executeQuery(
                "SELECT _id FROM " + products + " OFFSET 3")) {
                var ids = new ArrayList<String>();
                while (rows.next()) ids.add(rows.getString("_id"));

                assertEquals(List.of("4", "5"), ids, "a plain read pages without a limit");
            }
        }
    }

    /**
     * Past the cap the driver pays too much for the skip, so the statement is an error rather
     * than a read of far more points than it returns. Only the reads the driver skips itself are
     * capped: Qdrant does a search's skipping, and a system table is in memory already.
     */
    @Test
    void reportsAnOffsetDeeperThanTheDriverWillSkip() throws SQLException {
        long deep = Planner.MAX_OFFSET + 1;

        try (Statement statement = connection.createStatement()) {
            for (String sql : List.of(
                "SELECT _id FROM " + products + " OFFSET " + deep,
                "SELECT _id FROM " + products + " ORDER BY price LIMIT 2 OFFSET " + deep,
                "SELECT tags, COUNT(*) FROM " + products + " GROUP BY tags LIMIT 2 OFFSET "
                    + deep)) {
                SQLException error = assertThrows(SQLException.class,
                    () -> statement.executeQuery(sql), sql);

                assertTrue(error.getMessage().contains("OFFSET up to " + Planner.MAX_OFFSET),
                    error.getMessage());
            }
        }

        assertEquals(List.of(), read("SELECT _id FROM " + articles + " ORDER BY " + SEARCH
            + " LIMIT 2 OFFSET " + deep), "Qdrant skips for a search, so it isn't capped");
        assertEquals(List.of(), read("SELECT name FROM system.collections OFFSET " + deep));
    }

    /**
     * Each row as its single column, or as {@code value=count} for the two a group has.
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
