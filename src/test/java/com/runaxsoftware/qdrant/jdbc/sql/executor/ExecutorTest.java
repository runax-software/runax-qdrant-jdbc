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
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetPlan;
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

    @Test
    void rejectsASearchThatSaysNoLimit() {
        SQLException error = assertThrows(SQLException.class,
            () -> search(new ArrayList<>()).execute(plan(null), STATEMENT));

        assertEquals("42000", error.getSQLState());
        assertTrue(error.getMessage().contains("needs a LIMIT"), error.getMessage());
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
        int count = 0;

        try (ResultSet rows = SYSTEM.execute(
            new SystemTablePlan(table, table.columns(), limit), statement).resultSet()) {
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
        return new FacetPlan("docs", "lang",
            List.of(new FacetColumn("count", ColumnType.INTEGER, true)), null, limit);
    }

    private static ScrollPlan ordered(Long limit) {
        return new ScrollPlan("docs", List.of(ID), false, false, limit, null,
            new Ordering("price", true));
    }

    private static QueryPlan plan(Long limit) {
        return new QueryPlan("docs", List.of(ID, SCORE), "dense", List.of(0.1, 0.2), false, false,
            limit);
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
