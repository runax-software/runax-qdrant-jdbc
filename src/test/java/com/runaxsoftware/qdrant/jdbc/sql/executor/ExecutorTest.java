package com.runaxsoftware.qdrant.jdbc.sql.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runaxsoftware.qdrant.jdbc.embedding.Embedders;
import com.runaxsoftware.qdrant.jdbc.embedding.EmbeddingModel;
import com.runaxsoftware.qdrant.jdbc.gateway.models.MatrixPair;
import com.runaxsoftware.qdrant.jdbc.gateway.models.MatrixRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Document;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetHit;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Ordering;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollPage;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.Planner;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CountPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.EmbedText;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.GroupedQueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.MatrixColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.MatrixPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.PointPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SystemTablePlan;
import com.runaxsoftware.qdrant.jdbc.system.SystemColumn;
import com.runaxsoftware.qdrant.jdbc.system.SystemTable;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class ExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ColumnDefinition ID =
        new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, "");

    private static final ColumnDefinition SCORE = ColumnDefinition.score();

    private static final ColumnDefinition FIELD =
        new ColumnDefinition("name", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "name", "");

    // A statement without maximum rows or fetch size
    private static final Statement STATEMENT = statement(0, 0);

    private final Executor executor = new Executor(request -> {
        throw new AssertionError("Scrolled " + request.collection());
    });

    // An executor whose system tables hold two rows each, in the reader's own order
    private static final Executor SYSTEM = new Executor(request -> {
        throw new AssertionError("Scrolled " + request.collection());
    }, query -> {
        throw new AssertionError("Searched " + query.collection());
    }, (collection, filter) -> {
        throw new AssertionError("Counted " + collection);
    }, facet -> List.of(), table -> switch (table) {
        case ALIASES -> List.of(
            new Object[]{"best", "articles"}, new Object[]{"current", "products"});
        case COLLECTIONS -> List.of(
            collectionRow("articles", 7L), collectionRow("products", 42L));
        default -> throw new AssertionError("Read " + table);
    });

    // Constant plans

    @Test
    void runsAConstantPlanAsRowsOwnedByTheStatement() throws SQLException {
        ConstantPlan plan = new ConstantPlan(
            List.of(ResultColumn.of("one", Types.BIGINT), ResultColumn.of("?column?", Types.VARCHAR)),
            List.of(Arrays.asList(1L, "x")));

        ExecutionResult result = executor.execute(plan, STATEMENT);

        assertTrue(result.hasResultSet());
        assertEquals(-1, result.updateCount());

        try (ResultSet rows = result.resultSet()) {
            assertSame(STATEMENT, rows.getStatement());
            assertEquals(2, rows.getMetaData().getColumnCount());
            assertEquals("one", rows.getMetaData().getColumnLabel(1));
            assertEquals(Types.BIGINT, rows.getMetaData().getColumnType(1));
            assertEquals("?column?", rows.getMetaData().getColumnLabel(2));

            assertTrue(rows.next());
            assertEquals(1L, rows.getLong("one"));
            assertEquals("x", rows.getString(2));
            assertFalse(rows.next());
        }
    }

    @Test
    void keepsNullValues() throws SQLException {
        ConstantPlan plan = new ConstantPlan(List.of(ResultColumn.of("nothing", Types.VARCHAR)),
            List.of(Arrays.asList((Object) null)));

        try (ResultSet rows = executor.execute(plan, STATEMENT).resultSet()) {
            assertTrue(rows.next());
            assertEquals(null, rows.getString(1));
            assertTrue(rows.wasNull());
        }
    }

    @Test
    void runsAConstantPlanWithoutRows() throws SQLException {
        ConstantPlan plan = new ConstantPlan(List.of(ResultColumn.of("one", Types.BIGINT)), List.of());

        try (ResultSet rows = executor.execute(plan, STATEMENT).resultSet()) {
            assertEquals("one", rows.getMetaData().getColumnLabel(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void limitsAConstantPlanToTheMaximumRows() throws SQLException {
        ConstantPlan plan = new ConstantPlan(List.of(ResultColumn.of("one", Types.BIGINT)),
            List.of(List.of(1L), List.of(2L), List.of(3L)));

        try (ResultSet rows = executor.execute(plan, statement(2, 0)).resultSet()) {
            assertEquals(List.of(1L, 2L), longs(rows));
        }
    }

    @Test
    void returnsAFreshResultForEachRun() throws SQLException {
        ConstantPlan plan = new ConstantPlan(List.of(ResultColumn.of("one", Types.BIGINT)),
            List.of(List.of(1L)));

        ResultSet first = executor.execute(plan, STATEMENT).resultSet();
        ResultSet second = executor.execute(plan, STATEMENT).resultSet();

        assertNotSame(first, second);
        first.close();
        assertTrue(second.next(), "closing one result leaves the other readable");
        second.close();
    }

    @Test
    void runsWhatThePlannerProduces() throws SQLException {
        Planner planner = new Planner();
        ExecutionResult result = executor.execute(
            planner.plan(Parser.parse("SELECT 'keep alive' AS ping, 2.5"), name -> {
                throw new AssertionError("Looked up " + name);
            }), STATEMENT);

        try (ResultSet rows = result.resultSet()) {
            assertTrue(rows.next());
            assertEquals("keep alive", rows.getString("ping"));
            assertEquals(2.5, rows.getDouble(2));
        }
    }

    // Scroll plans: paging and limits

    @Test
    void readsEveryPageOfACollection() throws SQLException {
        FakeCollection docs = new FakeCollection(5);

        try (ResultSet rows = docs.run(scroll(null), statement(0, 2))) {
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), longs(rows));
        }

        assertEquals(List.of(2, 2, 2), docs.limits());
        assertEquals(Arrays.asList(null, "3", "5"), docs.offsets());
    }

    @Test
    void stopsAtTheLimitAndAsksOnlyForTheRowsItStillNeeds() throws SQLException {
        FakeCollection docs = new FakeCollection(10);

        try (ResultSet rows = docs.run(scroll(3L), statement(0, 2))) {
            assertEquals(List.of(1L, 2L, 3L), longs(rows));
        }

        assertEquals(List.of(2, 1), docs.limits());
    }

    @Test
    void stopsAtTheMaximumRowsWhenTheyAreFewerThanTheLimit() throws SQLException {
        FakeCollection docs = new FakeCollection(10);

        try (ResultSet rows = docs.run(scroll(5L), statement(2, 0))) {
            assertEquals(List.of(1L, 2L), longs(rows));
        }
        try (ResultSet rows = docs.run(scroll(2L), statement(5, 0))) {
            assertEquals(List.of(1L, 2L), longs(rows));
        }
        try (ResultSet rows = docs.run(scroll(null), statement(4, 0))) {
            assertEquals(List.of(1L, 2L, 3L, 4L), longs(rows));
        }
    }

    @Test
    void asksForNothingWhenTheLimitIsZero() throws SQLException {
        FakeCollection docs = new FakeCollection(3);

        try (ResultSet rows = docs.run(scroll(0L), STATEMENT)) {
            assertFalse(rows.next());
        }

        assertEquals(List.of(), docs.limits());
    }

    @Test
    void usesTheDefaultPageSizeAndCapsTheFetchSize() throws SQLException {
        FakeCollection docs = new FakeCollection(1);

        docs.run(scroll(null), statement(0, 0)).close();
        docs.run(scroll(null), statement(0, Executor.MAX_PAGE_SIZE + 1)).close();
        docs.run(scroll(null), statement(0, 7)).close();

        assertEquals(List.of(Executor.DEFAULT_PAGE_SIZE, Executor.MAX_PAGE_SIZE, 7), docs.limits());
    }

    @Test
    void returnsNoRowsForAnEmptyCollection() throws SQLException {
        try (ResultSet rows = new FakeCollection(0).run(scroll(null), STATEMENT)) {
            assertFalse(rows.next());
        }
    }

    @Test
    void reportsAFailedFirstPageWhenRun() {
        SQLException failure = new SQLException("Qdrant: Not found", "42S02");
        Executor failing = new Executor(request -> {
            throw failure;
        });

        assertSame(failure, assertThrows(SQLException.class,
            () -> failing.execute(scroll(null), STATEMENT)));
    }

    // Scroll plans: skipping an OFFSET

    /**
     * Qdrant pages a scroll by point ID, so the rows an OFFSET skips are read and dropped. The
     * requests ask for them on top of the rows the statement returns, and no second scroll
     * starts over: the cursor carries on from where the skipping left off.
     */
    @Test
    void skipsThePointsOfAnOffsetAsItPages() throws SQLException {
        FakeCollection docs = new FakeCollection(10);

        try (ResultSet rows = docs.run(scroll(3L, 4), statement(0, 2))) {
            assertEquals(List.of(5L, 6L, 7L), longs(rows));
        }

        assertEquals(List.of(2, 2, 2, 1), docs.limits(), "four points skipped, then three rows");
        assertEquals(Arrays.asList(null, "3", "5", "7"), docs.offsets());
    }

    @Test
    void skipsThePointsOfAnOffsetWithoutALimit() throws SQLException {
        FakeCollection docs = new FakeCollection(5);

        try (ResultSet rows = docs.run(scroll(null, 3), statement(0, 2))) {
            assertEquals(List.of(4L, 5L), longs(rows));
        }
    }

    @Test
    void returnsNoRowsWhenAnOffsetPassesTheLastPoint() throws SQLException {
        FakeCollection docs = new FakeCollection(3);

        try (ResultSet rows = docs.run(scroll(10L, 5), STATEMENT)) {
            assertFalse(rows.next());
        }
        try (ResultSet rows = docs.run(scroll(null, 3), STATEMENT)) {
            assertFalse(rows.next(), "an offset of the whole collection leaves nothing");
        }
    }

    /**
     * The maximum rows bound the rows the result set yields, as JDBC says, so they count after
     * the skip rather than against it.
     */
    @Test
    void countsTheMaximumRowsAfterTheSkipRatherThanAgainstIt() throws SQLException {
        FakeCollection docs = new FakeCollection(10);

        try (ResultSet rows = docs.run(scroll(null, 4), statement(2, 0))) {
            assertEquals(List.of(5L, 6L), longs(rows));
        }
    }

    @Test
    void asksForTheSkippedPointsAndTheRowsTogetherInOnePage() throws SQLException {
        var requests = new ArrayList<ScrollRequest>();

        // Unordered, with a page big enough for both: one request covers the skip and the rows
        scrolling(requests).execute(scroll(3L, 4), statement(0, 100)).resultSet().close();
        assertEquals(7, requests.get(0).limit());

        // Ordered: Qdrant answers in one page and rejects an offset, so that page holds both
        requests.clear();
        scrolling(requests).execute(ordered(5L, 3), statement(0, 10)).resultSet().close();
        assertEquals(8, requests.get(0).limit());
        assertEquals(new Ordering("price", true), requests.get(0).ordering());
        assertEquals(null, requests.get(0).offset(), "an ordered scroll sends Qdrant no offset");
    }

    @Test
    void skipsThePointsOfAnOrderedReadsOffsetFromItsOnePage() throws SQLException {
        FakeCollection docs = new FakeCollection(10);

        try (ResultSet rows = docs.run(ordered(3L, 4), statement(0, 2))) {
            assertEquals(List.of(5L, 6L, 7L), longs(rows));
        }

        assertEquals(List.of(7), docs.limits(), "one page, holding the skip and the rows");
    }

    @Test
    void asksForNothingWhenAnOffsetMeetsALimitOfNoRows() throws SQLException {
        FakeCollection docs = new FakeCollection(5);

        try (ResultSet rows = docs.run(scroll(0L, 3), STATEMENT)) {
            assertFalse(rows.next());
        }

        assertEquals(List.of(), docs.limits(), "no rows to return means nothing to skip for");
    }

    @Test
    void asksForPayloadsAndVectorsOnlyWhenThePlanNeedsThem() throws SQLException {
        List<ScrollRequest> requests = new ArrayList<>();
        Executor recording = new Executor(request -> {
            requests.add(request);
            return new ScrollPage(List.of(), null);
        });

        recording.execute(new ScrollPlan("docs", List.of(ID), false, true, null), STATEMENT)
            .resultSet().close();

        assertEquals("docs", requests.get(0).collection());
        assertFalse(requests.get(0).withPayload());
        assertTrue(requests.get(0).withVector());
    }

    // Scroll plans: values

    @Test
    void describesTheColumnsOfACollection() throws SQLException {
        ColumnDefinition price = new ColumnDefinition("price", ColumnKind.PAYLOAD_FIELD,
            ColumnType.FLOAT, 0, "price", "");
        ScrollPlan plan = new ScrollPlan("docs", List.of(ID, price), true, false, null);

        try (ResultSet rows = new FakeCollection(0).run(plan, STATEMENT)) {
            ResultSetMetaData metadata = rows.getMetaData();

            assertEquals("_id", metadata.getColumnLabel(1));
            assertEquals(ResultSetMetaData.columnNoNulls, metadata.isNullable(1));
            assertEquals("price", metadata.getColumnLabel(2));
            assertEquals(Types.DOUBLE, metadata.getColumnType(2));
            assertEquals("docs", metadata.getTableName(2));
            assertEquals(ResultSetMetaData.columnNullable, metadata.isNullable(2));
        }
    }

    @Test
    void readsIdsPayloadsAndPayloadFields() throws SQLException {
        JsonNode payload = json("""
            {"title": "Desk lamp", "price": 39.9, "stock": 3, "new": true,
             "specs": {"color": "black"}, "tags": ["office"], "nothing": null}""");
        Executor oneLamp = new Executor(request -> new ScrollPage(List.of(
            new Point(json("7"), payload, null),
            new Point(json("\"0b3e9a2c-7a51-4c6e-9d0f-2f4a6c8e1b37\""), json("{}"), null)), null));
        ScrollPlan plan = new ScrollPlan("docs", List.of(ID,
            field("title", ColumnType.TEXT), field("price", ColumnType.FLOAT),
            field("stock", ColumnType.INTEGER), field("new", ColumnType.BOOLEAN),
            field("specs.color", ColumnType.TEXT), field("tags", ColumnType.JSON),
            field("nothing", ColumnType.TEXT),
            new ColumnDefinition("_payload", ColumnKind.PAYLOAD, ColumnType.JSON, 0, null, "")),
            true, false, null);

        try (ResultSet rows = oneLamp.execute(plan, STATEMENT).resultSet()) {
            assertTrue(rows.next());
            assertEquals("7", rows.getString("_id"));
            assertEquals("Desk lamp", rows.getString("title"));
            assertEquals(39.9, rows.getDouble("price"));
            assertEquals(3L, rows.getLong("stock"));
            assertTrue(rows.getBoolean("new"));
            assertEquals("black", rows.getString("specs.color"));
            assertEquals("[\"office\"]", rows.getString("tags"));
            assertNull(rows.getString("nothing"));
            assertEquals(payload, json(rows.getString("_payload")));

            assertTrue(rows.next());
            assertEquals("0b3e9a2c-7a51-4c6e-9d0f-2f4a6c8e1b37", rows.getString("_id"));
            assertNull(rows.getString("title"), "a field the point doesn't have is null");
            assertTrue(rows.wasNull());
            assertFalse(rows.next());
        }
    }

    @Test
    void readsUnnamedAndNamedVectors() throws SQLException {
        Executor withVectors = new Executor(request -> new ScrollPage(List.of(
            new Point(json("1"), null, json("[0.5, 1.0]")),
            new Point(json("2"), null, json("{\"text\": [0.25]}")),
            new Point(json("3"), null, null)), null));
        ScrollPlan unnamed = new ScrollPlan("docs", List.of(vector("")), false, true, null);
        ScrollPlan named = new ScrollPlan("docs", List.of(vector("text")), false, true, null);

        try (ResultSet rows = withVectors.execute(unnamed, STATEMENT).resultSet()) {
            assertTrue(rows.next());
            assertEquals("[0.5,1.0]", rows.getString(1));
        }
        try (ResultSet rows = withVectors.execute(named, STATEMENT).resultSet()) {
            rows.next();
            assertNull(rows.getString(1), "an array has no named vector");
            rows.next();
            assertEquals("[0.25]", rows.getString(1));
            rows.next();
            assertNull(rows.getString(1), "no vectors were sent");
        }
    }

    // Facet plans

    @Test
    void runsAFacetPlanAsGroupedRowsOwnedByTheStatement() throws SQLException {
        var requests = new ArrayList<FacetRequest>();
        ExecutionResult result = faceting(requests).execute(new FacetPlan("docs", "lang",
            List.of(new FacetColumn("lang", ColumnType.TEXT, false),
                new FacetColumn("n", ColumnType.INTEGER, true)), null, 5L), STATEMENT);

        assertTrue(result.hasResultSet());
        assertEquals(-1, result.updateCount());
        assertEquals("docs", requests.get(0).collection());
        assertEquals("lang", requests.get(0).key());
        assertEquals(5L, requests.get(0).limit());

        try (ResultSet rows = result.resultSet()) {
            assertSame(STATEMENT, rows.getStatement());
            assertEquals(Types.VARCHAR, rows.getMetaData().getColumnType(1));
            assertEquals(Types.BIGINT, rows.getMetaData().getColumnType(2));
            assertEquals("docs", rows.getMetaData().getTableName(1));
            assertEquals(ResultSetMetaData.columnNoNulls, rows.getMetaData().isNullable(2));

            assertTrue(rows.next());
            assertEquals("en", rows.getString("lang"));
            assertEquals(3L, rows.getLong("n"));
            assertTrue(rows.next());
            assertEquals("de", rows.getString("lang"));
            assertEquals(1L, rows.getLong("n"));
            assertFalse(rows.next());
        }
    }

    @Test
    void readsAGroupedValueAsItsOwnType() throws SQLException {
        assertEquals(2024L, grouped(JSON.getNodeFactory().numberNode(2024), ColumnType.INTEGER));
        assertEquals(true, grouped(JSON.getNodeFactory().booleanNode(true), ColumnType.BOOLEAN));
        assertEquals("en", grouped(JSON.getNodeFactory().textNode("en"), ColumnType.TEXT));
    }

    @Test
    void asksQdrantForAsManyGroupsAsTheStatementReads() throws SQLException {
        var requests = new ArrayList<FacetRequest>();

        faceting(requests).execute(facet(3L), statement(0, 0)).resultSet().close();
        faceting(requests).execute(facet(null), statement(7, 0)).resultSet().close();
        faceting(requests).execute(facet(9L), statement(4, 0)).resultSet().close();

        assertEquals(List.of(3L, 7L, 4L), requests.stream().map(FacetRequest::limit).toList());
    }

    @Test
    void groupsNothingForALimitOfNoRows() throws SQLException {
        var requests = new ArrayList<FacetRequest>();

        try (ResultSet rows = faceting(requests).execute(facet(0L), STATEMENT).resultSet()) {
            assertFalse(rows.next());
        }

        assertEquals(List.of(), requests, "no call counts no groups");
    }

    @Test
    void rejectsAGroupedStatementThatSaysNoLimit() {
        SQLException error = assertThrows(SQLException.class,
            () -> faceting(new ArrayList<>()).execute(facet(null), STATEMENT));

        assertEquals("42000", error.getSQLState());
        assertTrue(error.getMessage().contains("GROUP BY needs a LIMIT"), error.getMessage());
    }

    /**
     * Qdrant's facet API has no offset, and ignores one rather than reporting it, so the driver
     * counts the groups it returns and the ones it skips together and drops the first.
     */
    @Test
    void countsTheSkippedGroupsAndTheReturnedOnesTogetherAndDropsTheFirst() throws SQLException {
        var requests = new ArrayList<FacetRequest>();

        try (ResultSet rows = faceting(requests).execute(facet(1L, 1), STATEMENT).resultSet()) {
            assertTrue(rows.next());
            assertEquals(1L, rows.getLong("count"), "the second group, 'de', counted once");
            assertFalse(rows.next());
        }

        assertEquals(List.of(2L), requests.stream().map(FacetRequest::limit).toList());
    }

    @Test
    void returnsNoGroupsWhenAnOffsetPassesTheLastOne() throws SQLException {
        var requests = new ArrayList<FacetRequest>();

        try (ResultSet rows = faceting(requests).execute(facet(5L, 9), STATEMENT).resultSet()) {
            assertFalse(rows.next(), "the fake counts two groups, the offset passes both");
        }

        assertEquals(List.of(14L), requests.stream().map(FacetRequest::limit).toList());
    }

    @Test
    void countsTheMaximumRowsOfAGroupedReadAfterTheSkip() throws SQLException {
        var requests = new ArrayList<FacetRequest>();

        faceting(requests).execute(facet(null, 3), statement(2, 0)).resultSet().close();

        assertEquals(List.of(5L), requests.stream().map(FacetRequest::limit).toList());
    }

    @Test
    void sendsTheFilterOfAGroupedStatement() throws SQLException {
        JsonNode filter = JSON.createObjectNode().put("key", "lang");
        var requests = new ArrayList<FacetRequest>();

        faceting(requests).execute(new FacetPlan("docs", "lang",
            List.of(new FacetColumn("n", ColumnType.INTEGER, true)), filter, 5L), STATEMENT)
            .resultSet().close();

        assertSame(filter, requests.get(0).filter());
    }

    @Test
    void reportsWhatCountingTheGroupsReported() {
        SQLException failure = new SQLException("Qdrant: Not found", "42S02");
        Executor failing = new Executor(request -> {
            throw new AssertionError("Scrolled " + request.collection());
        }, query -> List.of(), (collection, filter) -> 0, facet -> {
            throw failure;
        }, table -> List.of());

        assertSame(failure, assertThrows(SQLException.class,
            () -> failing.execute(facet(5L), STATEMENT)));
    }

    // The connection's default limit

    @Test
    void readsTheConnectionsDefaultLimitWhenTheStatementGivesNone() throws SQLException {
        var scrolls = new ArrayList<ScrollRequest>();
        var searches = new ArrayList<QueryRequest>();
        var facets = new ArrayList<FacetRequest>();

        bounded(scrolls, searches, facets, 3)
            .execute(ordered(null), STATEMENT).resultSet().close();
        bounded(scrolls, searches, facets, 3)
            .execute(plan(null), STATEMENT).resultSet().close();
        bounded(scrolls, searches, facets, 3)
            .execute(facet(null), STATEMENT).resultSet().close();

        assertEquals(3, scrolls.get(0).limit(), "the ordered read");
        assertEquals(3L, searches.get(0).limit(), "the search");
        assertEquals(3L, facets.get(0).limit(), "the groups");
    }

    @Test
    void warnsThatItsOwnLimitBoundedTheRead() throws SQLException {
        for (Plan plan : List.of(ordered(null), plan(null), facet(null))) {
            ExecutionResult result = bounded(new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), 3).execute(plan, STATEMENT);

            assertTrue(result.warning().contains("at most 3 rows"), result.warning());
            assertTrue(result.warning().contains("defaultLimit"), result.warning());
            result.resultSet().close();
        }
    }

    @Test
    void saysNothingWhenTheStatementGaveItsOwnLimit() throws SQLException {
        Executor executor = bounded(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 3);

        assertEquals(null, executor.execute(ordered(2L), STATEMENT).warning());
        assertEquals(null, executor.execute(plan(2L), STATEMENT).warning());
        assertEquals(null, executor.execute(facet(2L), STATEMENT).warning());
        assertEquals(null, executor.execute(ordered(null), statement(2, 0)).warning(),
            "the maximum rows said how many");
    }

    /**
     * A limit bounds the rows a read returns, so an OFFSET skips ahead of them whichever of the
     * three supplied it: the over-fetch is that limit plus the offset, and the default still
     * bounds exactly as many rows as its warning claims.
     */
    @Test
    void addsTheOffsetToTheConnectionsDefaultLimit() throws SQLException {
        var scrolls = new ArrayList<ScrollRequest>();
        var searches = new ArrayList<QueryRequest>();
        var facets = new ArrayList<FacetRequest>();

        ExecutionResult read = bounded(scrolls, searches, facets, 3).execute(ordered(null, 2),
            STATEMENT);
        bounded(scrolls, searches, facets, 3).execute(plan(null, 2), STATEMENT).resultSet().close();
        bounded(scrolls, searches, facets, 3).execute(facet(null, 2), STATEMENT).resultSet()
            .close();

        assertEquals(5, scrolls.get(0).limit(), "the ordered read over-fetches 3 + 2");
        assertEquals(5L, facets.get(0).limit(), "the groups over-fetch 3 + 2");

        assertEquals(3L, searches.get(0).limit(), "Qdrant skips for a search, so the limit stays");
        assertEquals(2L, searches.get(0).offset());

        // The warning counts the rows the statement will see, not the ones fetched to skip
        assertTrue(read.warning().contains("at most 3 rows"), read.warning());
        assertFalse(read.warning().contains("5 rows"), read.warning());
        read.resultSet().close();
    }

    @Test
    void asksForTheLimitWhenTheConnectionHasNoDefault() {
        for (Plan plan : List.of(ordered(null), plan(null), facet(null))) {
            SQLException error = assertThrows(SQLException.class, () -> bounded(new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), 0).execute(plan, STATEMENT));

            assertEquals("42000", error.getSQLState());
            assertTrue(error.getMessage().contains("needs a LIMIT"), error.getMessage());
        }
    }

    /**
     * An OFFSET doesn't stand in for the limit a one-call read needs: with no default to fall
     * back on, the read still asks for a LIMIT, and for the same reason as without an offset.
     */
    @Test
    void stillAsksForTheLimitWhenAnOffsetIsAllTheStatementGave() {
        for (Plan plan : List.of(ordered(null, 2), plan(null, 2), facet(null, 2))) {
            SQLException error = assertThrows(SQLException.class, () -> bounded(new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), 0).execute(plan, STATEMENT));

            assertEquals("42000", error.getSQLState());
            assertTrue(error.getMessage().contains("needs a LIMIT"), error.getMessage());
        }
    }

    /**
     * A plain read pages through the cursor, so it never needed a limit and the default never
     * bounded it: an OFFSET alone is enough to page one, whatever the connection's default.
     */
    @Test
    void pagesAPlainReadWithAnOffsetAndNoLimitWhateverTheDefault() throws SQLException {
        for (int defaultLimit : List.of(0, 3)) {
            FakeCollection docs = new FakeCollection(5);

            try (ResultSet rows = new Executor(docs::scroll, query -> List.of(),
                (collection, filter) -> 0, facet -> List.of(), table -> List.of(), null,
                Embedders.none(), defaultLimit)
                .execute(scroll(null, 3), STATEMENT).resultSet()) {
                assertEquals(List.of(4L, 5L), longs(rows), "defaultLimit " + defaultLimit);
            }
        }
    }

    // Ordered scroll plans

    @Test
    void asksForTheWholeOrderedReadInOnePage() throws SQLException {
        var requests = new ArrayList<ScrollRequest>();
        Executor executor = scrolling(requests);

        // Ordered: Qdrant answers in one page, so the fetch size mustn't cut the rows down
        executor.execute(ordered(2500L), statement(0, 10)).resultSet().close();
        assertEquals(2500, requests.get(0).limit());
        assertEquals(new Ordering("price", true), requests.get(0).ordering());

        // Unordered: paged with the cursor, so the fetch size sets the page
        requests.clear();
        executor.execute(new ScrollPlan("docs", List.of(ID), false, false, 2500L),
            statement(0, 10)).resultSet().close();
        assertEquals(10, requests.get(0).limit());
        assertEquals(null, requests.get(0).ordering());
    }

    @Test
    void readsAnOrderedScrollUpToTheStatementsMaximumRows() throws SQLException {
        var requests = new ArrayList<ScrollRequest>();

        scrolling(requests).execute(ordered(null), statement(4, 0)).resultSet().close();

        assertEquals(4, requests.get(0).limit());
    }

    @Test
    void rejectsAnOrderedScrollThatSaysNoLimit() {
        SQLException error = assertThrows(SQLException.class,
            () -> scrolling(new ArrayList<>()).execute(ordered(null), STATEMENT));

        assertEquals("42000", error.getSQLState());
        assertTrue(error.getMessage().contains("ORDER BY needs a LIMIT"), error.getMessage());
    }

    // Query plans

    @Test
    void runsAQueryPlanAsScoredRowsOwnedByTheStatement() throws SQLException {
        var requests = new ArrayList<QueryRequest>();
        ExecutionResult result = search(requests).execute(
            new QueryPlan("docs", List.of(ID, SCORE, FIELD), "dense", List.of(0.1, 0.2), true,
                false, 5L), STATEMENT);

        assertTrue(result.hasResultSet());
        assertEquals(-1, result.updateCount());
        assertEquals(1, requests.size());
        assertEquals("docs", requests.get(0).collection());
        assertEquals("dense", requests.get(0).vector());
        assertEquals(List.of(0.1, 0.2), requests.get(0).query());
        assertEquals(5L, requests.get(0).limit());
        assertTrue(requests.get(0).withPayload());
        assertFalse(requests.get(0).withVector());

        try (ResultSet rows = result.resultSet()) {
            assertSame(STATEMENT, rows.getStatement());
            assertEquals(Types.DOUBLE, rows.getMetaData().getColumnType(2));
            assertEquals("docs", rows.getMetaData().getTableName(2));

            assertTrue(rows.next());
            assertEquals("1", rows.getString("_id"));
            assertEquals(0.9, rows.getDouble("_score"));
            assertEquals("Lamp", rows.getString("name"));

            assertTrue(rows.next());
            assertEquals("2", rows.getString("_id"));
            assertEquals(0.5, rows.getDouble("_score"));
            assertNull(rows.getString("name"), "the second point has no payload");
            assertFalse(rows.next());
        }
    }

    @Test
    void asksQdrantForAsManyPointsAsTheSearchReturns() throws SQLException {
        var requests = new ArrayList<QueryRequest>();

        search(requests).execute(plan(1L), statement(0, 0)).resultSet().close();
        search(requests).execute(plan(null), statement(3, 0)).resultSet().close();
        search(requests).execute(plan(7L), statement(3, 0)).resultSet().close();
        search(requests).execute(plan(2L), statement(9, 0)).resultSet().close();

        assertEquals(List.of(1L, 3L, 3L, 2L),
            requests.stream().map(QueryRequest::limit).toList());
    }

    @Test
    void readsOnePointAsOneRowAndAsksForNothingItWontReturn() throws SQLException {
        var asked = new ArrayList<String>();
        Executor reading = new Executor(new Scroller() {
            @Override
            public ScrollPage scroll(ScrollRequest request) {
                throw new AssertionError("Scrolled " + request.collection());
            }

            @Override
            public Point point(String collection, JsonNode id) {
                asked.add(collection + " " + id);

                return id.asInt() == 7 ? new Point(id, JSON.createObjectNode().put("name", "Lamp"),
                    null) : null;
            }
        });
        JsonNode seven = JSON.getNodeFactory().numberNode(7);

        try (ResultSet rows = reading.execute(new PointPlan("docs", seven, List.of(ID), null, 0),
            STATEMENT).resultSet()) {
            assertEquals(List.of("7"), ids(rows));
        }

        try (ResultSet rows = reading.execute(new PointPlan("docs",
            JSON.getNodeFactory().numberNode(8), List.of(ID), null, 0), STATEMENT).resultSet()) {
            assertFalse(rows.next(), "no such point is no row");
        }

        assertEquals(List.of("docs 7", "docs 8"), asked);

        reading.execute(new PointPlan("docs", seven, List.of(ID), 0L, 0), STATEMENT)
            .resultSet().close();
        reading.execute(new PointPlan("docs", seven, List.of(ID), 5L, 1), STATEMENT)
            .resultSet().close();

        assertEquals(2, asked.size(), "a limit of no rows and an offset past the row ask nothing");
    }

    @Test
    void readsAMatrixsRowsInQdrantsOrderAndTakesTheOffsetAndLimitItself() throws SQLException {
        var asked = new ArrayList<MatrixRequest>();
        var request = new MatrixRequest("docs", "dense", 3L, 1L, null, true);
        Executor matrix = new Executor(scroll -> {
            throw new AssertionError("Scrolled " + scroll.collection());
        }, new Searcher() {
            @Override
            public List<ScoredPoint> search(QueryRequest query) {
                throw new AssertionError("Searched");
            }

            @Override
            public List<MatrixPair> matrix(MatrixRequest matrix) {
                asked.add(matrix);

                return List.of(
                    new MatrixPair(JSON.getNodeFactory().numberNode(1),
                        JSON.getNodeFactory().textNode("5c56"), 0.9, 0L, 1L),
                    new MatrixPair(JSON.getNodeFactory().textNode("5c56"),
                        JSON.getNodeFactory().numberNode(3), 0.8, 1L, 2L),
                    new MatrixPair(JSON.getNodeFactory().numberNode(3),
                        JSON.getNodeFactory().numberNode(1), 0.7, 2L, 0L));
            }
        }, (collection, filter) -> 0, facet -> List.of(), table -> List.of());

        try (ResultSet rows = matrix.execute(new MatrixPlan(request,
            List.of(MatrixColumn.B, MatrixColumn.SCORE, MatrixColumn.ROW, MatrixColumn.COL),
            List.of("neighbour", "_score", "_row", "_col"), 5L, 1), statement(0, 0)).resultSet()) {
            assertEquals("neighbour", rows.getMetaData().getColumnLabel(1));
            assertEquals(Types.DOUBLE, rows.getMetaData().getColumnType(2));
            assertEquals(Types.BIGINT, rows.getMetaData().getColumnType(3));

            assertTrue(rows.next());
            assertEquals("3", rows.getString("neighbour"), "an ID is text, as _id is");
            assertEquals(0.8, rows.getDouble("_score"));
            assertEquals(1, rows.getLong("_row"));
            assertEquals(2, rows.getLong("_col"));
            assertTrue(rows.next());
            assertFalse(rows.next());
        }

        assertEquals(List.of(request), asked);

        try (ResultSet rows = matrix.execute(new MatrixPlan(request, List.of(MatrixColumn.A),
            List.of("_a"), null, 0), statement(2, 0)).resultSet()) {
            assertEquals(List.of("1", "5c56"), ids(rows), "the statement's maximum rows bound it");
        }

        try (ResultSet rows = matrix.execute(new MatrixPlan(request, List.of(MatrixColumn.A),
            List.of("_a"), 0L, 0), statement(0, 0)).resultSet()) {
            assertFalse(rows.next());
        }

        assertEquals(2, asked.size(), "a limit of no rows asks for no matrix");
    }

    @Test
    void ordersAGroupedSearchsPointsByScoreAndCountsRowsNotGroups() throws SQLException {
        var asked = new ArrayList<String>();

        // Group a's two points, then group b's: the second of a is worse than the first of b
        List<ScoredPoint> grouped = List.of(scored(1, 0.9), scored(2, 0.4), scored(3, 0.7),
            scored(4, 0.1));

        try (ResultSet rows = grouping(asked, grouped).execute(new GroupedQueryPlan(
            plan(3L, 1), "doc_id", 2, false), STATEMENT).resultSet()) {
            assertEquals(List.of("3", "2", "4"), ids(rows),
                "by score, best first, after the one row the OFFSET skips");
        }

        assertEquals(List.of("doc_id x2, 4 groups, offset 0"), asked,
            "as many groups as rows to read, since Qdrant counts groups and skips none");

        try (ResultSet rows = grouping(asked, grouped).execute(new GroupedQueryPlan(
            plan(2L), "doc_id", 2, true), STATEMENT).resultSet()) {
            assertEquals(List.of("4", "2"), ids(rows), "a distance ranks the lowest score first");
        }
    }

    @Test
    void asksForNoGroupsWhenTheLimitIsNoRows() throws SQLException {
        var asked = new ArrayList<String>();

        try (ResultSet rows = grouping(asked, List.of()).execute(new GroupedQueryPlan(
            plan(0L), "doc_id", 2, false), STATEMENT).resultSet()) {
            assertFalse(rows.next());
        }

        assertEquals(List.of(), asked);
    }

    @Test
    void runsSeveralSearchesAndReturnsEachOnesRowsInOrder() throws SQLException {
        var requests = new ArrayList<QueryRequest>();

        List<ExecutionResult> results = search(requests).executeSearches(
            List.of(plan(1L), plan(0L), plan(null, 4)), statement(2, 0));

        assertEquals(3, results.size());
        assertEquals(List.of(1L, 2L), requests.stream().map(QueryRequest::limit).toList(),
            "the search of no rows isn't sent, and the last is bounded by the maximum rows");
        assertEquals(4, requests.get(1).offset());

        assertEquals(List.of(1, 0, 2), results.stream().map(result -> {
            try (ResultSet rows = result.resultSet()) {
                int count = 0;
                while (rows.next()) count++;
                return count;
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        }).toList());
    }

    @Test
    void returnsOnlyTheRowsTheLimitAsksFor() throws SQLException {
        try (ResultSet rows = search(new ArrayList<>())
            .execute(plan(1L), STATEMENT).resultSet()) {
            assertTrue(rows.next());
            assertFalse(rows.next(), "the fake scores two points, the limit asks for one");
        }
    }

    @Test
    void searchesNothingForALimitOfNoRows() throws SQLException {
        var requests = new ArrayList<QueryRequest>();

        try (ResultSet rows = search(requests).execute(plan(0L), STATEMENT).resultSet()) {
            assertFalse(rows.next());
        }

        assertEquals(List.of(), requests, "no call ranks no points");
    }

    /**
     * The Query API's offset is a row count, so it goes to Qdrant as it was written and the
     * limit still counts the points returned after it: the driver discards nothing.
     */
    @Test
    void passesASearchsOffsetToQdrant() throws SQLException {
        var requests = new ArrayList<QueryRequest>();

        search(requests).execute(plan(5L, 2), STATEMENT).resultSet().close();
        search(requests).execute(plan(5L, 0), STATEMENT).resultSet().close();
        search(requests).execute(plan(null, 3), statement(4, 0)).resultSet().close();

        assertEquals(List.of(2L, 0L, 3L), requests.stream().map(QueryRequest::offset).toList());
        assertEquals(List.of(5L, 5L, 4L), requests.stream().map(QueryRequest::limit).toList(),
            "the maximum rows bound the rows returned, not the ones Qdrant passes over");
    }

    @Test
    void searchesNothingWhenAnOffsetMeetsALimitOfNoRows() throws SQLException {
        var requests = new ArrayList<QueryRequest>();

        try (ResultSet rows = search(requests).execute(plan(0L, 5), STATEMENT).resultSet()) {
            assertFalse(rows.next());
        }

        assertEquals(List.of(), requests, "no call ranks no points");
    }

    @Test
    void rejectsASearchThatSaysNoLimit() {
        SQLException error = assertThrows(SQLException.class,
            () -> search(new ArrayList<>()).execute(plan(null), STATEMENT));

        assertEquals("42000", error.getSQLState());
        assertTrue(error.getMessage().contains("needs a LIMIT"), error.getMessage());
    }

    @Test
    void asksForTheWordsToRankAFieldByAndSaysWhatASearchByTextNeeds() throws SQLException {
        var searches = new ArrayList<QueryRequest>();
        Executor defaulting = bounded(new ArrayList<>(), searches, new ArrayList<>(), 3);

        ExecutionResult result = defaulting.execute(byText(null), STATEMENT);

        assertEquals("refund policy", searches.get(0).text());
        assertEquals("title", searches.get(0).vector());
        assertEquals(List.of(), searches.get(0).query());
        assertNull(searches.get(0).document());
        assertEquals("A search by text read at most 3 rows, the connection's defaultLimit, "
            + "because the statement gave no LIMIT and set no maximum rows", result.warning());

        assertEquals("A search by text needs a LIMIT: Qdrant ranks the points that match best, "
            + "not the whole collection", assertThrows(SQLException.class,
            () -> search(new ArrayList<>()).execute(byText(null), STATEMENT)).getMessage());
    }

    private static QueryPlan byText(Long limit) {
        return new QueryPlan("docs", List.of(ID, SCORE), "title", List.of(), false, false, limit,
            0, null, null, "refund policy");
    }

    @Test
    void reportsWhatSearchingReported() {
        SQLException failure = new SQLException("Qdrant: Not found", "42S02");
        Executor failing = new Executor(request -> {
            throw new AssertionError("Scrolled " + request.collection());
        }, query -> {
            throw failure;
        }, (collection, filter) -> {
            throw new AssertionError("Counted " + collection);
        }, facet -> List.of(), table -> List.of());

        assertSame(failure, assertThrows(SQLException.class,
            () -> failing.execute(plan(5L), STATEMENT)));
    }

    // Count plans

    @Test
    void runsACountPlanAsOneRowOwnedByTheStatement() throws SQLException {
        var counted = new ArrayList<String>();
        ExecutionResult result = counting(counted, 42)
            .execute(new CountPlan("docs", "count", null, null), STATEMENT);

        assertTrue(result.hasResultSet());
        assertEquals(-1, result.updateCount());
        assertEquals(List.of("docs"), counted);

        try (ResultSet rows = result.resultSet()) {
            ResultSetMetaData columns = rows.getMetaData();

            assertSame(STATEMENT, rows.getStatement());
            assertEquals(1, columns.getColumnCount());
            assertEquals("count", columns.getColumnLabel(1));
            assertEquals(Types.BIGINT, columns.getColumnType(1));
            assertEquals("BIGINT", columns.getColumnTypeName(1));
            assertEquals("", columns.getTableName(1), "a count is no column of the collection");
            assertEquals(ResultSetMetaData.columnNoNulls, columns.isNullable(1));

            assertTrue(rows.next());
            assertEquals(42L, rows.getLong(1));
            assertFalse(rows.next(), "a count is one row");
        }
    }

    @Test
    void namesTheCountColumnAsThePlanAsks() throws SQLException {
        try (ResultSet rows = counting(new ArrayList<>(), 7)
            .execute(new CountPlan("docs", "total", null, null), STATEMENT).resultSet()) {
            assertEquals("total", rows.getMetaData().getColumnLabel(1));
            assertTrue(rows.next());
            assertEquals(7L, rows.getLong("total"));
        }
    }

    @Test
    void countsNothingForALimitOfNoRows() throws SQLException {
        var counted = new ArrayList<String>();

        try (ResultSet rows = counting(counted, 42)
            .execute(new CountPlan("docs", "count", null, 0L), STATEMENT).resultSet()) {
            assertEquals(1, rows.getMetaData().getColumnCount());
            assertFalse(rows.next());
        }

        assertEquals(List.of(), counted, "no call counts a row nobody reads");
    }

    @Test
    void countsOnceWhateverTheLimitAndMaximumRowsAllow() throws SQLException {
        var counted = new ArrayList<String>();

        assertEquals(List.of(42L), counts(counting(counted, 42), 1L, STATEMENT));
        assertEquals(List.of(42L), counts(counting(counted, 42), 5L, statement(2, 0)));
        assertEquals(List.of(42L), counts(counting(counted, 42), null, statement(1, 0)));
        assertEquals(List.of("docs", "docs", "docs"), counted, "one call each, and only one");
    }

    /**
     * A count is one row, so an offset of one is already past it: no row and nothing counted,
     * the way a limit of no rows leaves it out.
     */
    @Test
    void countsNothingWhenAnOffsetSkipsItsOneRow() throws SQLException {
        var counted = new ArrayList<String>();

        try (ResultSet rows = counting(counted, 42)
            .execute(new CountPlan("docs", "count", null, null, 1), STATEMENT).resultSet()) {
            assertEquals(1, rows.getMetaData().getColumnCount());
            assertFalse(rows.next());
        }

        assertEquals(List.of(), counted, "no call counts a row nobody reads");

        // And OFFSET 0 is no offset at all, so the row stays
        assertEquals(List.of(42L), counts(counting(counted, 42), null, STATEMENT));
    }

    @Test
    void reportsWhatCountingReported() {
        SQLException failure = new SQLException("Qdrant: Not found", "42S02");
        Executor failing = new Executor(request -> {
            throw new AssertionError("Scrolled " + request.collection());
        }, query -> {
            throw new AssertionError("Searched " + query.collection());
        }, (collection, filter) -> {
            throw failure;
        }, facet -> List.of(), table -> List.of());

        assertSame(failure, assertThrows(SQLException.class,
            () -> failing.execute(new CountPlan("docs", "count", null, null), STATEMENT)));
    }

    // System table plans

    @Test
    void runsASystemTablePlanAsRowsOwnedByTheStatement() throws SQLException {
        ExecutionResult result = SYSTEM.execute(
            new SystemTablePlan(SystemTable.ALIASES, SystemTable.ALIASES.columns(), null),
            STATEMENT);

        assertTrue(result.hasResultSet());
        assertEquals(-1, result.updateCount());

        try (ResultSet rows = result.resultSet()) {
            assertSame(STATEMENT, rows.getStatement());
            assertTrue(rows.next());
            assertEquals("best", rows.getString("alias_name"));
            assertEquals("articles", rows.getString("collection_name"));
            assertTrue(rows.next());
            assertEquals("current", rows.getString("alias_name"));
            assertFalse(rows.next());
        }
    }

    @Test
    void describesTheColumnsOfASystemTable() throws SQLException {
        SystemColumn count = SystemTable.COLLECTIONS.column("points_count").orElseThrow();

        try (ResultSet rows = SYSTEM.execute(
            new SystemTablePlan(SystemTable.COLLECTIONS, List.of(count.as("points")), null),
            STATEMENT).resultSet()) {
            ResultSetMetaData columns = rows.getMetaData();

            assertEquals(1, columns.getColumnCount());
            assertEquals("points", columns.getColumnLabel(1));
            assertEquals(Types.BIGINT, columns.getColumnType(1));
            assertEquals("BIGINT", columns.getColumnTypeName(1));
            assertEquals("collections", columns.getTableName(1));
            assertEquals(ResultSetMetaData.columnNullable, columns.isNullable(1));
        }
    }

    @Test
    void readsOnlyTheColumnsASystemTablePlanNames() throws SQLException {
        SystemTable table = SystemTable.COLLECTIONS;
        List<SystemColumn> columns = List.of(
            table.column("status").orElseThrow(), table.column("name").orElseThrow());

        try (ResultSet rows = SYSTEM.execute(
            new SystemTablePlan(table, columns, null), STATEMENT).resultSet()) {
            assertTrue(rows.next());
            assertEquals(2, rows.getMetaData().getColumnCount());
            assertEquals("green", rows.getString(1));
            assertEquals("articles", rows.getString(2));
        }
    }

    @Test
    void stopsASystemTableAtItsLimit() throws SQLException {
        assertEquals(1, systemRows(SystemTable.ALIASES, 1L, STATEMENT));
        assertEquals(2, systemRows(SystemTable.ALIASES, 5L, STATEMENT));
        assertEquals(0, systemRows(SystemTable.ALIASES, 0L, STATEMENT));
    }

    @Test
    void stopsASystemTableAtTheStatementsMaximumRows() throws SQLException {
        assertEquals(1, systemRows(SystemTable.ALIASES, null, statement(1, 0)));
        assertEquals(1, systemRows(SystemTable.ALIASES, 2L, statement(1, 0)));
        assertEquals(1, systemRows(SystemTable.ALIASES, 1L, statement(5, 0)));
    }

    /**
     * The table's rows are in memory already, so an OFFSET just starts further along them, and
     * the limit counts the rows returned after it.
     */
    @Test
    void skipsTheFirstRowsOfASystemTable() throws SQLException {
        SystemTable aliases = SystemTable.ALIASES;

        try (ResultSet rows = SYSTEM.execute(
            new SystemTablePlan(aliases, aliases.columns(), null, 1), STATEMENT).resultSet()) {
            assertTrue(rows.next());
            assertEquals("current", rows.getString("alias_name"), "the second row");
            assertFalse(rows.next());
        }

        assertEquals(1, systemRows(aliases, 1L, 1, STATEMENT), "the limit counts after the skip");
        assertEquals(0, systemRows(aliases, 5L, 2, STATEMENT), "an offset past the last row");
        assertEquals(2, systemRows(aliases, null, 0, STATEMENT));
    }

    @Test
    void reportsWhatReadingASystemTableReported() {
        SQLException failure = new SQLException("Qdrant: Service internal error", "HY000");
        Executor failing = new Executor(request -> {
            throw new AssertionError("Scrolled " + request.collection());
        }, query -> {
            throw new AssertionError("Searched " + query.collection());
        }, (collection, filter) -> {
            throw new AssertionError("Counted " + collection);
        }, facet -> List.of(), table -> {
            throw failure;
        });

        assertSame(failure, assertThrows(SQLException.class, () -> failing.execute(
            new SystemTablePlan(SystemTable.ALIASES, SystemTable.ALIASES.columns(), null),
            STATEMENT)));
    }

    // Helpers

    private static int systemRows(SystemTable table, Long limit, Statement statement)
        throws SQLException {
        return systemRows(table, limit, 0, statement);
    }

    private static int systemRows(SystemTable table, Long limit, long offset, Statement statement)
        throws SQLException {
        int count = 0;

        try (ResultSet rows = SYSTEM.execute(
            new SystemTablePlan(table, table.columns(), limit, offset), statement).resultSet()) {
            while (rows.next()) count++;
        }

        return count;
    }

    /**
     * An executor whose searches score two points, the first with a payload and the second
     * without, and which records the requests it was given.
     */
    private static Executor search(List<QueryRequest> requests) {
        return new Executor(request -> {
            throw new AssertionError("Scrolled " + request.collection());
        }, query -> {
            requests.add(query);

            return List.of(
                new ScoredPoint(new Point(JSON.getNodeFactory().numberNode(1),
                    JSON.createObjectNode().put("name", "Lamp"), null), 0.9),
                new ScoredPoint(new Point(JSON.getNodeFactory().numberNode(2), null, null), 0.5))
                .subList(0, (int) Math.min(2, query.limit()));
        }, (collection, filter) -> {
            throw new AssertionError("Counted " + collection);
        }, facet -> List.of(), table -> List.of());
    }

    /**
     * An executor whose counts answer the same number and which records the collections it was
     * asked to count.
     */
    private static Executor counting(List<String> counted, long count) {
        return new Executor(request -> {
            throw new AssertionError("Scrolled " + request.collection());
        }, query -> {
            throw new AssertionError("Searched " + query.collection());
        }, (collection, filter) -> {
            counted.add(collection);

            return count;
        }, facet -> List.of(), table -> List.of());
    }

    // The counts a plan's rows hold
    private static List<Long> counts(Executor executor, Long limit, Statement statement)
        throws SQLException {
        try (ResultSet rows = executor.execute(new CountPlan("docs", "count", null, limit), statement)
            .resultSet()) {
            return longs(rows);
        }
    }

    /**
     * An executor whose scrolls answer with no points, recording what they were asked for.
     */
    private static Executor scrolling(List<ScrollRequest> requests) {
        return new Executor(request -> {
            requests.add(request);

            return new ScrollPage(List.of(), null);
        }, query -> List.of(), (collection, filter) -> 0, facet -> List.of(), table -> List.of());
    }

    /**
     * An executor whose facets count two groups, recording what they were asked for.
     */
    private static Executor faceting(List<FacetRequest> requests) {
        return new Executor(request -> {
            throw new AssertionError("Scrolled " + request.collection());
        }, query -> List.of(), (collection, filter) -> 0, facet -> {
            requests.add(facet);

            return List.of(new FacetHit(JSON.getNodeFactory().textNode("en"), 3),
                new FacetHit(JSON.getNodeFactory().textNode("de"), 1));
        }, table -> List.of());
    }

    /**
     * The one value a facet of this hit reads, as a column of this type.
     */
    private static Object grouped(JsonNode value, ColumnType type) throws SQLException {
        Executor executor = new Executor(request -> {
            throw new AssertionError("Scrolled " + request.collection());
        }, query -> List.of(), (collection, filter) -> 0,
            facet -> List.of(new FacetHit(value, 1)), table -> List.of());

        try (ResultSet rows = executor.execute(new FacetPlan("docs", "field",
            List.of(new FacetColumn("field", type, false)), null, 5L), STATEMENT).resultSet()) {
            assertTrue(rows.next());

            return rows.getObject(1);
        }
    }

    private static FacetPlan facet(Long limit) {
        return facet(limit, 0);
    }

    private static FacetPlan facet(Long limit, long offset) {
        return new FacetPlan("docs", "lang",
            List.of(new FacetColumn("count", ColumnType.INTEGER, true)), null, limit, offset);
    }

    // Embedding the text a search searches with

    @Test
    void sendsTheTextForQdrantToEmbedWithAQdrantModel() throws SQLException {
        List<QueryRequest> requests = new ArrayList<>();

        searching(requests, provider -> {
            throw new AssertionError("Called " + provider);
        }).execute(embedded("qdrant:qdrant/bm25", 0), STATEMENT).resultSet().close();

        assertEquals(new Document("refund policy", "qdrant/bm25"), requests.get(0).document());
        assertEquals(List.of(), requests.get(0).query());
        assertEquals("keywords", requests.get(0).vector());
    }

    @Test
    void embedsTheTextWithTheProviderAndSearchesWithItsVector() throws SQLException {
        List<QueryRequest> requests = new ArrayList<>();
        List<String> embedded = new ArrayList<>();

        searching(requests, provider -> (model, role, texts) -> {
            embedded.add(provider + " " + model + " " + role + " " + String.join("|", texts));
            return texts.stream().map(x -> List.of(0.1, 0.2)).toList();
        }).execute(embedded("openai:text-embedding-3-small", 2), STATEMENT).resultSet().close();

        assertEquals(List.of("OPENAI openai:text-embedding-3-small QUERY refund policy"), embedded);
        assertEquals(List.of(0.1, 0.2), requests.get(0).query());
        assertNull(requests.get(0).document(), "Qdrant gets the vector, not the text");
    }

    @Test
    void rejectsAVectorOfOtherDimensionsThanTheSearchedOne() {
        Executor executor = searching(new ArrayList<>(), provider -> (model, role, texts) -> texts.stream().map(x -> List.of(0.1, 0.2, 0.3)).toList());

        SQLException error = assertThrows(SQLException.class,
            () -> executor.execute(embedded("openai:text-embedding-3-small", 2), STATEMENT));

        assertEquals("openai:text-embedding-3-small made a vector of 3 dimensions, and 'keywords' "
            + "of docs has 2: it was embedded with another model", error.getMessage());
    }

    @Test
    void passesOnAProviderThatIsntSetUp() {
        Executor executor = searching(new ArrayList<>(), provider -> {
            throw new SQLException("Embedding with OpenAI needs its API key", "28000");
        });

        assertEquals("28000", assertThrows(SQLException.class,
            () -> executor.execute(embedded("openai:text-embedding-3-small", 2), STATEMENT))
            .getSQLState());
    }

    @Test
    void explainsThatAQdrantWithoutInferenceCantUseAHostedModel() {
        Executor local = new Executor(request -> {
            throw new AssertionError("Scrolled");
        }, query -> {
            throw new SQLException("Qdrant: Service internal error: InferenceService URL not "
                + "configured - please provide valid address in config", "HY000");
        }, (collection, filter) -> 0, facet -> List.of(), table -> List.of());

        SQLException error = assertThrows(SQLException.class, () -> local.execute(
            embedded("qdrant:sentence-transformers/all-minilm-l6-v2", 0), STATEMENT));

        assertEquals("This Qdrant can't embed text with 'qdrant:sentence-transformers/all-minilm-l6-v2'"
            + ": it runs no inference service, which hosted models need (Qdrant Cloud has one). "
            + "Embed with a model the driver calls, as openai:text-embedding-3-small, or with "
            + "qdrant:qdrant/bm25 for a sparse vector", error.getMessage());
        assertEquals("HY000", error.getSQLState());
    }

    // An executor whose searches find nothing, recording what they were asked for
    private static Executor searching(List<QueryRequest> requests, Embedders embedders) {
        return new Executor(request -> {
            throw new AssertionError("Scrolled");
        }, query -> {
            requests.add(query);
            return List.of();
        }, (collection, filter) -> 0, facet -> List.of(), table -> List.of(), null, embedders);
    }

    private static QueryPlan embedded(String model, int dimensions) {
        try {
            return new QueryPlan("docs", List.of(ID, SCORE), "keywords", List.of(), false, false,
                5L, null, new EmbedText(EmbeddingModel.parse(model), "refund policy", dimensions));
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * An executor with a connection default limit, recording every request it sends.
     */
    private static Executor bounded(List<ScrollRequest> scrolls, List<QueryRequest> searches,
                                    List<FacetRequest> facets, int defaultLimit) {
        return new Executor(request -> {
            scrolls.add(request);

            return new ScrollPage(List.of(), null);
        }, query -> {
            searches.add(query);

            return List.of();
        }, (collection, filter) -> 0, facet -> {
            facets.add(facet);

            return List.of();
        }, table -> List.of(), null, Embedders.none(), defaultLimit);
    }

    private static ScrollPlan ordered(Long limit) {
        return ordered(limit, 0);
    }

    private static ScrollPlan ordered(Long limit, long offset) {
        return new ScrollPlan("docs", List.of(ID), false, false, limit, offset, null,
            new Ordering("price", true));
    }

    private static ScoredPoint scored(int id, double score) {
        return new ScoredPoint(new Point(JSON.getNodeFactory().numberNode(id), null, null), score);
    }

    private static List<String> ids(ResultSet rows) throws SQLException {
        var ids = new ArrayList<String>();
        while (rows.next()) ids.add(rows.getString(1));

        return ids;
    }

    // An executor whose grouped search answers with these points and records what it was asked
    private static Executor grouping(List<String> asked, List<ScoredPoint> points) {
        return new Executor(request -> {
            throw new AssertionError("Scrolled " + request.collection());
        }, new Searcher() {
            @Override
            public List<ScoredPoint> search(QueryRequest query) {
                throw new AssertionError("Searched without groups");
            }

            @Override
            public List<ScoredPoint> searchGroups(QueryRequest query, String groupBy,
                                                  long groupSize) {
                asked.add(groupBy + " x" + groupSize + ", " + query.limit() + " groups, offset "
                    + query.offset());

                return points;
            }
        }, (collection, filter) -> 0, facet -> List.of(), table -> List.of());
    }

    private static QueryPlan plan(Long limit) {
        return plan(limit, 0);
    }

    private static QueryPlan plan(Long limit, long offset) {
        return new QueryPlan("docs", List.of(ID, SCORE), "dense", List.of(0.1, 0.2), false, false,
            limit, offset, null);
    }

    // A statement that answers maxRows and fetchSize and fails on anything else
    private static Statement statement(long maxRows, int fetchSize) {
        return (Statement) Proxy.newProxyInstance(ExecutorTest.class.getClassLoader(),
            new Class<?>[] {Statement.class}, (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "getLargeMaxRows":
                        return maxRows;
                    case "getFetchSize":
                        return fetchSize;
                    case "toString":
                        return "statement";
                    default:
                        throw new AssertionError("Called Statement." + method.getName());
                }
            });
    }

    // A system.collections row with only the columns these tests read
    private static Object[] collectionRow(String name, long points) {
        SystemTable table = SystemTable.COLLECTIONS;
        Object[] row = new Object[table.columns().size()];

        row[table.indexOf(table.column("name").orElseThrow())] = name;
        row[table.indexOf(table.column("status").orElseThrow())] = "green";
        row[table.indexOf(table.column("points_count").orElseThrow())] = points;

        return row;
    }

    private static ScrollPlan scroll(Long limit) {
        return new ScrollPlan("docs", List.of(ID), false, false, limit);
    }

    private static ScrollPlan scroll(Long limit, long offset) {
        return new ScrollPlan("docs", List.of(ID), false, false, limit, offset, null, null);
    }

    private static ColumnDefinition field(String path, ColumnType type) {
        return new ColumnDefinition(path, ColumnKind.PAYLOAD_FIELD, type, 0, path, "");
    }

    private static ColumnDefinition vector(String name) {
        return new ColumnDefinition(name.isEmpty() ? "_vector" : name, ColumnKind.VECTOR,
            ColumnType.VECTOR, 1, name, "");
    }

    private static List<Long> longs(ResultSet rows) throws SQLException {
        List<Long> values = new ArrayList<>();
        while (rows.next()) {
            values.add(rows.getLong(1));
        }
        return values;
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Points 1 to {@code size}, scrolled the way Qdrant does: from the offset's ID, at most the
     * request's limit, with the next page's first ID as the next offset.
     */
    private static final class FakeCollection {
        private final int size;
        private final List<ScrollRequest> requests = new ArrayList<>();

        FakeCollection(int size) {
            this.size = size;
        }

        ResultSet run(ScrollPlan plan, Statement statement) throws SQLException {
            return new Executor(this::scroll).execute(plan, statement).resultSet();
        }

        List<Integer> limits() {
            return requests.stream().map(ScrollRequest::limit).toList();
        }

        List<String> offsets() {
            return requests.stream()
                .map(request -> request.offset() == null ? null : request.offset().asText())
                .toList();
        }

        private ScrollPage scroll(ScrollRequest request) {
            requests.add(request);

            long first = request.offset() == null ? 1 : request.offset().asLong();
            long end = Math.min(size + 1L, first + request.limit());
            List<Point> points = new ArrayList<>();

            for (long id = first; id < end; id++) {
                points.add(new Point(JSON.getNodeFactory().numberNode(id), null, null));
            }

            return new ScrollPage(points, end <= size ? JSON.getNodeFactory().numberNode(end) : null);
        }
    }
}
