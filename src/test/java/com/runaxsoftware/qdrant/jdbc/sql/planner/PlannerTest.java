package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.Ordering;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CountPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.FacetPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SystemTablePlan;
import com.runaxsoftware.qdrant.jdbc.system.SystemColumn;
import com.runaxsoftware.qdrant.jdbc.system.SystemTable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

class PlannerTest {

    private static final TableSchema DOCS = new TableSchema(
            new QdrantCollection("docs", 3, List.of(new VectorField("", VectorKind.DENSE, 4, "Cosine")), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("_payload", ColumnKind.PAYLOAD, ColumnType.JSON, 0, null, ""),
                    new ColumnDefinition("title", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "title", ""),
                    new ColumnDefinition("price", ColumnKind.PAYLOAD_FIELD, ColumnType.FLOAT, 0, "price", ""),
                    new ColumnDefinition("_vector", ColumnKind.VECTOR, ColumnType.VECTOR, 4, "", "")));

    private static final TableSchema PRODUCTS = new TableSchema(
            new QdrantCollection("products", 1, List.of(), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("specs", ColumnKind.PAYLOAD_FIELD, ColumnType.JSON, 0, "specs", ""),
                    new ColumnDefinition("specs.color", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "specs.color", "")));

    private static final TableSchema NAMED = new TableSchema(
            new QdrantCollection("named", 2, List.of(
                    new VectorField("dense", VectorKind.DENSE, 2, "Cosine"),
                    new VectorField("colbert", VectorKind.MULTIVECTOR, 2, "Cosine"),
                    new VectorField("sparse", VectorKind.SPARSE, 0, null)), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("dense", ColumnKind.VECTOR, ColumnType.VECTOR, 2, "dense", ""),
                    new ColumnDefinition("colbert", ColumnKind.VECTOR, ColumnType.MULTIVECTOR, 2, "colbert", ""),
                    new ColumnDefinition("sparse", ColumnKind.VECTOR, ColumnType.SPARSE_VECTOR, 0, "sparse", "")));

    private static final TableSchema INDEXED = new TableSchema(
            new QdrantCollection("indexed", 3, List.of(), Map.of(
                    "price", PayloadIndexType.FLOAT,
                    "year", PayloadIndexType.INTEGER,
                    "created_at", PayloadIndexType.DATETIME,
                    "name", PayloadIndexType.KEYWORD,
                    "tags", PayloadIndexType.KEYWORD,
                    "in_stock", PayloadIndexType.BOOL)),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("name", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "name", ""),
                    new ColumnDefinition("year", ColumnKind.PAYLOAD_FIELD, ColumnType.INTEGER, 0, "year", ""),
                    new ColumnDefinition("price", ColumnKind.PAYLOAD_FIELD, ColumnType.FLOAT, 0, "price", ""),
                    new ColumnDefinition("created_at", ColumnKind.PAYLOAD_FIELD, ColumnType.DATETIME, 0, "created_at", ""),
                    new ColumnDefinition("tags", ColumnKind.PAYLOAD_FIELD, ColumnType.JSON, 0, "tags", ""),
                    new ColumnDefinition("in_stock", ColumnKind.PAYLOAD_FIELD, ColumnType.BOOLEAN, 0, "in_stock", ""),
                    new ColumnDefinition("_payload", ColumnKind.PAYLOAD, ColumnType.JSON, 0, null, "")));

    private static final Catalog CATALOG = name -> {
        if (name.equals("docs")) {
            return DOCS;
        }
        if (name.equals("products")) {
            return PRODUCTS;
        }
        if (name.equals("named")) {
            return NAMED;
        }
        if (name.equals("indexed")) {
            return INDEXED;
        }

        throw new SQLException("Collection " + name + " doesn't exist", "42S02");
    };

    // Proves a SELECT without FROM never looks up a collection
    private static final Catalog NO_LOOKUPS = name -> {
        throw new AssertionError("Looked up " + name);
    };

    private final Planner planner = new Planner();

    // SELECT without FROM

    @Test
    void plansConstantsAsOneRowWithoutLookingUpCollections() throws SQLException {
        assertEquals(
                new ConstantPlan(
                        List.of(ResultColumn.of("letter", Types.VARCHAR), ResultColumn.of("?column?", Types.BIGINT),
                                ResultColumn.of("?column?", Types.DOUBLE), ResultColumn.of("yes", Types.BOOLEAN)),
                        List.of(List.of("a", 1L, 2.5, true))),
                plan("SELECT 'a' AS letter, 1, 2.5, true AS yes", NO_LOOKUPS));
    }

    @Test
    void plansNoRowsWhenLimitOrOffsetSkipsTheRow() throws SQLException {
        assertEquals(List.of(), ((ConstantPlan) plan("SELECT 1 LIMIT 0", NO_LOOKUPS)).rows());
        assertEquals(List.of(), ((ConstantPlan) plan("SELECT 1 OFFSET 1", NO_LOOKUPS)).rows());
        assertEquals(1, ((ConstantPlan) plan("SELECT 1 LIMIT 5 OFFSET 0", NO_LOOKUPS)).rows().size());
    }

    @Test
    void rejectsColumnsWithoutFrom() {
        SQLSyntaxErrorException error =
                assertThrows(SQLSyntaxErrorException.class, () -> plan("SELECT name", NO_LOOKUPS));

        assertEquals("Unknown column 'name': the SELECT has no FROM", error.getMessage());
        assertEquals("42S22", error.getSQLState());
    }

    @Test
    void rejectsStarWithoutFrom() {
        assertThrows(SQLSyntaxErrorException.class, () -> plan("SELECT *", NO_LOOKUPS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 1 WHERE a = 1", "SELECT 1 ORDER BY a", "SELECT ?"})
    void rejectsWhatASelectWithoutFromCantDoYet(String sql) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> plan(sql, NO_LOOKUPS));
    }

    // SELECT with FROM

    @Test
    void selectStarSkipsVectors() throws SQLException {
        ScrollPlan plan = scroll("SELECT * FROM docs");

        assertEquals(List.of("_id", "_payload", "title", "price"), names(plan));
        assertEquals("docs", plan.collection());
        assertTrue(plan.withPayload());
        assertFalse(plan.withVector());
        assertEquals(null, plan.limit());
    }

    @Test
    void fetchesOnlyWhatTheColumnsNeed() throws SQLException {
        ScrollPlan ids = scroll("SELECT _id FROM docs LIMIT 5");
        assertFalse(ids.withPayload());
        assertFalse(ids.withVector());
        assertEquals(5L, ids.limit());

        assertTrue(scroll("SELECT _payload FROM docs").withPayload());
        assertTrue(scroll("SELECT price FROM docs").withPayload());
        assertTrue(scroll("SELECT _vector FROM docs").withVector());
    }

    @Test
    void renamesAliasedColumns() throws SQLException {
        ColumnDefinition column = scroll("SELECT title AS name FROM docs").columns().get(0);

        assertEquals("name", column.name());
        assertEquals("title", column.source());
        assertEquals(ColumnKind.PAYLOAD_FIELD, column.kind());
    }

    @Test
    void rejectsUnknownColumns() {
        SQLSyntaxErrorException error =
                assertThrows(SQLSyntaxErrorException.class, () -> plan("SELECT pric FROM docs", CATALOG));

        assertEquals("Unknown column 'pric' in docs", error.getMessage());
        assertEquals("42S22", error.getSQLState());
    }

    @Test
    void passesUnknownCollectionsOnFromTheCatalog() {
        assertEquals("42S02",
                assertThrows(SQLException.class, () -> plan("SELECT * FROM missing", CATALOG)).getSQLState());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM docs ORDER BY price",
            "SELECT 1 FROM docs"})
    void rejectsWhatASelectWithFromCantDoYet(String sql) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> plan(sql, CATALOG));
    }

    // OFFSET

    /**
     * Qdrant's scroll {@code offset} is the point ID a page starts at, not a row count, so the
     * plan carries the rows to skip and the driver discards them as it pages.
     */
    @Test
    void plansAReadsOffsetAsRowsTheDriverSkips() throws SQLException {
        ScrollPlan plan = scroll("SELECT _id FROM docs LIMIT 10 OFFSET 20");

        assertEquals(10L, plan.limit());
        assertEquals(20, plan.offset());
    }

    @Test
    void plansAnOffsetWithoutALimit() throws SQLException {
        // A plain read pages with the cursor, so it needs no limit to say where to stop
        assertEquals(20, scroll("SELECT _id FROM docs OFFSET 20").offset());
        assertEquals(null, scroll("SELECT _id FROM docs OFFSET 20").limit());
    }

    @Test
    void plansNoOffsetAtAllForOffsetZeroAndForNone() throws SQLException {
        assertEquals(0, scroll("SELECT _id FROM docs").offset());
        assertEquals(0, scroll("SELECT _id FROM docs OFFSET 0").offset());
    }

    @Test
    void plansAnOrderedReadsOffsetAsRowsTheDriverSkips() throws SQLException {
        // Qdrant rejects an offset beside an order_by, so this one is the driver's to skip too
        ScrollPlan plan = scroll("SELECT _id FROM indexed ORDER BY price LIMIT 5 OFFSET 3");

        assertEquals(new Ordering("price", false), plan.ordering());
        assertEquals(5L, plan.limit());
        assertEquals(3, plan.offset());
    }

    @Test
    void plansASearchsOffsetForQdrantToApply() throws SQLException {
        // The Query API's offset is a row count, so it goes to Qdrant as it was written
        QueryPlan plan = query("SELECT _id FROM docs "
                + "ORDER BY similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC LIMIT 5 OFFSET 2");

        assertEquals(5L, plan.limit());
        assertEquals(2, plan.offset());
    }

    @Test
    void plansAGroupedReadsOffsetAsGroupsTheDriverSkips() throws SQLException {
        FacetPlan plan = facet("SELECT name, COUNT(*) FROM indexed GROUP BY name LIMIT 5 OFFSET 2");

        assertEquals(5L, plan.limit());
        assertEquals(2, plan.offset());
    }

    @Test
    void plansACountsOffsetAsSkippingItsOneRow() throws SQLException {
        assertEquals(0, count("SELECT COUNT(*) FROM docs OFFSET 0", NO_LOOKUPS).offset());
        assertEquals(1, count("SELECT COUNT(*) FROM docs OFFSET 1", NO_LOOKUPS).offset());
    }

    @Test
    void plansASystemTablesOffsetAsRowsToSkipInMemory() throws SQLException {
        assertEquals(5, system("SELECT * FROM system.collections OFFSET 5").offset());
    }

    /**
     * A plain or ordered read and a GROUP BY pay for their own skip, so the planner caps it:
     * past the cap the statement would read far more rows than it returns, which is the full
     * scan this driver doesn't do quietly.
     */
    @Test
    void rejectsAnOffsetDeeperThanTheDriverWillSkip() {
        for (String sql : List.of(
                "SELECT _id FROM docs OFFSET ",
                "SELECT _id FROM indexed ORDER BY price LIMIT 5 OFFSET ",
                "SELECT name, COUNT(*) FROM indexed GROUP BY name LIMIT 5 OFFSET ")) {
            SQLException error = assertThrows(SQLFeatureNotSupportedException.class,
                    () -> plan(sql + (Planner.MAX_OFFSET + 1), CATALOG), sql);

            assertTrue(error.getMessage().contains("OFFSET up to " + Planner.MAX_OFFSET
                    + " runs"), error.getMessage());
        }
    }

    @Test
    void skipsAsDeepAsTheCapAllows() throws SQLException {
        assertEquals(Planner.MAX_OFFSET,
                scroll("SELECT _id FROM docs OFFSET " + Planner.MAX_OFFSET).offset());
    }

    /**
     * Qdrant does the skipping for a search, and a system table is read into memory whatever the
     * statement asks for, so neither pays for a deep offset and neither is capped.
     */
    @Test
    void leavesTheOffsetUncappedWhereTheDriverPaysNothingForIt() throws SQLException {
        long deep = Planner.MAX_OFFSET * 100;

        assertEquals(deep, query("SELECT _id FROM docs ORDER BY "
                + "similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC LIMIT 5 OFFSET " + deep)
                .offset());
        assertEquals(deep, system("SELECT * FROM system.collections OFFSET " + deep).offset());
        assertEquals(deep, count("SELECT COUNT(*) FROM docs OFFSET " + deep, NO_LOOKUPS).offset());
    }

    // Grouping

    @Test
    void groupsByAFieldQdrantCanCountTheValuesOf() throws SQLException {
        FacetPlan plan = facet("SELECT name, COUNT(*) FROM indexed GROUP BY name LIMIT 20");

        assertEquals("indexed", plan.collection());
        assertEquals("name", plan.key());
        assertEquals(20L, plan.limit());
        assertEquals(null, plan.filter());
        assertEquals(List.of("name", "count"), names(plan));
        assertEquals(List.of(ColumnType.TEXT, ColumnType.INTEGER), types(plan));
        assertEquals(List.of(false, true), counting(plan));
    }

    @Test
    void groupsByAWholeNumberAndABoolean() throws SQLException {
        assertEquals(List.of(ColumnType.INTEGER, ColumnType.INTEGER),
                types(facet("SELECT year, COUNT(*) FROM indexed GROUP BY year LIMIT 5")));
        assertEquals(List.of(ColumnType.BOOLEAN, ColumnType.INTEGER),
                types(facet("SELECT in_stock, COUNT(*) FROM indexed GROUP BY in_stock LIMIT 5")));
    }

    /**
     * An array field is a JSON column, but Qdrant counts the values its index holds, so each
     * group of it is one keyword.
     */
    @Test
    void groupsByTheValuesAnIndexHoldsRatherThanTheColumnsType() throws SQLException {
        assertEquals(List.of(ColumnType.TEXT, ColumnType.INTEGER),
                types(facet("SELECT tags, COUNT(*) FROM indexed GROUP BY tags LIMIT 5")));
    }

    @Test
    void readsTheGroupsAndTheCountInTheStatementsOrder() throws SQLException {
        assertEquals(List.of("count", "name"),
                names(facet("SELECT COUNT(*), name FROM indexed GROUP BY name LIMIT 5")));
        assertEquals(List.of("n", "group"),
                names(facet("SELECT COUNT(*) AS n, name AS \"group\" FROM indexed "
                        + "GROUP BY name LIMIT 5")));
        assertEquals(List.of("count"),
                names(facet("SELECT COUNT(*) FROM indexed GROUP BY name LIMIT 5")));
    }

    /**
     * A GROUP BY without a count reads the values themselves, which is what the facet answers
     * and what the SQL asks for.
     */
    @Test
    void groupsWithoutCountingTheGroups() throws SQLException {
        FacetPlan plan = facet("SELECT name FROM indexed GROUP BY name LIMIT 5");

        assertEquals(List.of("name"), names(plan));
        assertEquals(List.of(false), counting(plan));
    }

    @Test
    void groupsUnderTheTablesName() throws SQLException {
        assertEquals("name", facet("SELECT t.name, COUNT(*) FROM indexed t GROUP BY t.name "
                + "LIMIT 5").key());
    }

    @Test
    void filtersTheGroupedPoints() throws SQLException {
        FacetPlan plan = facet("SELECT name, COUNT(*) FROM indexed WHERE year = 2024 "
                + "GROUP BY name LIMIT 5");

        assertEquals("{\"must\":[{\"key\":\"year\",\"match\":{\"value\":2024}}]}",
                plan.filter().toString());
    }

    @Test
    void rejectsGroupingByAFieldQdrantCantCountTheValuesOf() {
        SQLException number = assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT price, COUNT(*) FROM indexed GROUP BY price LIMIT 5", CATALOG));
        assertTrue(number.getMessage().contains("indexed as float"), number.getMessage());

        SQLException missing = assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT title, COUNT(*) FROM docs GROUP BY title LIMIT 5", CATALOG));
        assertTrue(missing.getMessage().contains("not indexed"), missing.getMessage());

        for (String column : List.of("_id", "_payload")) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> plan("SELECT COUNT(*) FROM "
                    + "indexed GROUP BY " + column + " LIMIT 5", CATALOG), column);
        }
    }

    @Test
    void rejectsAnUngroupedColumnBesideTheCount() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT year, COUNT(*) FROM indexed GROUP BY name LIMIT 5", CATALOG));

        assertEquals("Column 'year' of indexed isn't grouped: a grouped statement selects the "
                + "field it groups by, 'name', and COUNT(*)", error.getMessage());
        assertEquals("42S22", error.getSQLState());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT COUNT(*) FROM indexed GROUP BY name, year LIMIT 5",
            "SELECT name, COUNT(*) FROM indexed GROUP BY name ORDER BY name LIMIT 5",
            "SELECT * FROM indexed GROUP BY name LIMIT 5"})
    void rejectsWhatAGroupedStatementCantDoYet(String sql) {
        assertThrows(SQLException.class, () -> plan(sql, CATALOG), sql);
    }

    // Ordering

    @Test
    void ordersByARangeIndexedField() throws SQLException {
        assertEquals(new Ordering("price", false),
                scroll("SELECT _id FROM indexed ORDER BY price LIMIT 5").ordering());
        assertEquals(new Ordering("price", true),
                scroll("SELECT _id FROM indexed ORDER BY price DESC LIMIT 5").ordering());
        assertEquals(new Ordering("year", false),
                scroll("SELECT _id FROM indexed ORDER BY year ASC LIMIT 5").ordering());
        assertEquals(new Ordering("created_at", true),
                scroll("SELECT _id FROM indexed t ORDER BY t.created_at DESC LIMIT 5").ordering());
    }

    @Test
    void ordersAFilteredReadToo() throws SQLException {
        ScrollPlan plan = scroll("SELECT _id FROM indexed WHERE name = 'Lamp' "
                + "ORDER BY price DESC LIMIT 5");

        assertEquals(new Ordering("price", true), plan.ordering());
        assertEquals("{\"must\":[{\"key\":\"name\",\"match\":{\"value\":\"Lamp\"}}]}",
                plan.filter().toString());
    }

    @Test
    void readsPointsInQdrantsOwnOrderForTheIdColumn() throws SQLException {
        // Which is the order a scroll reads in anyway, so it needs no ordering and no limit
        ScrollPlan plan = scroll("SELECT _id FROM indexed ORDER BY _id");

        assertEquals(null, plan.ordering());
        assertEquals(null, plan.limit());
    }

    @Test
    void rejectsReadingPointsFromTheHighestIdDown() {
        SQLException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT _id FROM indexed ORDER BY _id DESC LIMIT 5", CATALOG));

        assertTrue(error.getMessage().contains("ORDER BY _id DESC isn't supported"),
                error.getMessage());
    }

    @Test
    void rejectsOrderingByAFieldQdrantCantOrderBy() {
        SQLException keyword = assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT _id FROM indexed ORDER BY name LIMIT 5", CATALOG));
        assertTrue(keyword.getMessage().contains("indexed as keyword"), keyword.getMessage());

        SQLException missing = assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT _id FROM docs ORDER BY price LIMIT 5", CATALOG));
        assertTrue(missing.getMessage().contains("not indexed"), missing.getMessage());

        assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT _id FROM indexed ORDER BY _payload LIMIT 5", CATALOG));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT _id FROM docs ORDER BY _vector LIMIT 5", CATALOG));
    }

    // Similarity search

    @Test
    void plansASearchOfTheCollectionsVector() throws SQLException {
        QueryPlan plan = query("SELECT _id, _score, title FROM docs "
                + "ORDER BY similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC LIMIT 10");

        assertEquals("docs", plan.collection());
        assertEquals("", plan.vector(), "the collection's single unnamed vector");
        assertEquals(List.of(0.1, 0.2, 0.3, 0.4), plan.query());
        assertEquals(List.of("_id", "_score", "title"), names(plan.columns()));
        assertEquals(10L, plan.limit());
        assertTrue(plan.withPayload(), "title is a payload field");
        assertFalse(plan.withVector(), "no column names a vector");
    }

    @Test
    void plansASearchOfANamedVector() throws SQLException {
        QueryPlan plan = query("SELECT _id FROM named ORDER BY similarity(dense, [1, 2]) DESC");

        assertEquals("dense", plan.vector());
        assertEquals(List.of(1.0, 2.0), plan.query());
        assertEquals(null, plan.limit(), "the statement's maximum rows may still bound it");
        assertFalse(plan.withPayload());
    }

    @Test
    void readsTheSearchedVectorUnderTheTablesName() throws SQLException {
        assertEquals("", query("SELECT _id FROM docs t "
                + "ORDER BY similarity(t._vector, [0.1, 0.2, 0.3, 0.4]) DESC").vector());
        assertEquals("", query("SELECT _id FROM docs "
                + "ORDER BY SIMILARITY(docs._vector, [0.1, 0.2, 0.3, 0.4]) DESC").vector());
    }

    @Test
    void fetchesTheVectorOnlyWhenTheSearchNamesItAsAColumn() throws SQLException {
        assertTrue(query("SELECT _vector, _score FROM docs "
                + "ORDER BY similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC").withVector());
        assertFalse(query("SELECT _score FROM docs "
                + "ORDER BY similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC").withVector());
    }

    @Test
    void leavesTheScoreOutOfAStar() throws SQLException {
        assertEquals(List.of("_id", "_payload", "title", "price"),
                names(query("SELECT * FROM docs "
                        + "ORDER BY similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC").columns()));
    }

    @Test
    void describesTheScoreAsADouble() throws SQLException {
        ColumnDefinition score = query("SELECT _score FROM docs "
                + "ORDER BY similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC").columns().get(0);

        assertEquals("_score", score.name());
        assertEquals(ColumnKind.SCORE, score.kind());
        assertEquals(ColumnType.FLOAT, score.type());
    }

    @Test
    void renamesTheScore() throws SQLException {
        assertEquals(List.of("closeness"), names(query("SELECT _score AS closeness FROM docs "
                + "ORDER BY similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC").columns()));
    }

    @Test
    void rejectsTheScoreOutsideASearch() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT _id, _score FROM docs", CATALOG));

        assertEquals("Column '_score' is only in a similarity search: add ORDER BY "
                + "similarity(vector, [0.1, ...]) DESC", error.getMessage());
        assertEquals("42S22", error.getSQLState());
    }

    @Test
    void rejectsAVectorOfTheWrongSize() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT _id FROM docs ORDER BY similarity(_vector, [0.1, 0.2]) DESC",
                        CATALOG));

        assertEquals("Vector '_vector' of docs has 4 dimensions, the vector searching it has 2",
                error.getMessage());
        assertEquals("42000", error.getSQLState());
    }

    @Test
    void rejectsSearchingWhatIsNotAVector() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT _id FROM docs ORDER BY similarity(title, [0.1]) DESC", CATALOG));

        assertEquals("Column 'title' of docs isn't a vector: a similarity search searches a "
                + "vector column", error.getMessage());

        assertEquals("42S22", assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT _id FROM docs ORDER BY similarity(nope, [0.1]) DESC", CATALOG))
                .getSQLState());
    }

    @Test
    void rejectsSearchingASparseOrMultiVector() {
        for (String vector : List.of("sparse", "colbert")) {
            SQLException error = assertThrows(SQLFeatureNotSupportedException.class,
                    () -> plan("SELECT _id FROM named ORDER BY similarity(" + vector
                            + ", [0.1, 0.2]) DESC", CATALOG));

            assertTrue(error.getMessage().contains("isn't supported yet"), error.getMessage());
        }
    }

    @Test
    void rejectsASearchThatIsNotRankedBestFirst() {
        for (String ordering : List.of("", " ASC")) {
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> plan("SELECT _id FROM docs ORDER BY similarity(_vector, "
                            + "[0.1, 0.2, 0.3, 0.4])" + ordering, CATALOG));
        }
    }

    @Test
    void rejectsWhatASearchCantTakeYet() {
        // A vector to embed, a parameter, and a vector written as a string
        for (String query : List.of("embed('refund')", "?", "'[0.1, 0.2, 0.3, 0.4]'")) {
            assertThrows(SQLException.class,
                    () -> plan("SELECT _id FROM docs ORDER BY similarity(_vector, " + query
                            + ") DESC", CATALOG), query);
        }
    }

    @Test
    void rejectsACallThatIsNotASearch() {
        SQLException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT _id FROM docs ORDER BY closeness(_vector, [0.1]) DESC",
                        CATALOG));

        assertTrue(error.getMessage().startsWith("'closeness' isn't a function this driver has"),
                error.getMessage());
    }

    @Test
    void rejectsOrderingBySeveralThings() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT _id FROM docs ORDER BY similarity(_vector, "
                        + "[0.1, 0.2, 0.3, 0.4]) DESC, price", CATALOG));
    }

    @Test
    void rejectsASearchOfASystemTable() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT name FROM system.collections "
                        + "ORDER BY similarity(name, [0.1]) DESC", NO_LOOKUPS));
    }

    // Counting

    @Test
    void plansACountWithoutLookingUpTheCollectionsColumns() throws SQLException {
        // Counting reads no column, so it costs the one call Qdrant counts with
        CountPlan plan = count("SELECT COUNT(*) FROM docs", NO_LOOKUPS);

        assertEquals("docs", plan.collection());
        assertEquals("count", plan.column(), "the name PostgreSQL gives an unnamed count");
        assertEquals(null, plan.limit());
    }

    @Test
    void plansACountHoweverItIsWritten() throws SQLException {
        assertEquals("count", count("select count(*) from docs", NO_LOOKUPS).column());
        assertEquals("total", count("SELECT COUNT(*) AS total FROM docs", NO_LOOKUPS).column());
        assertEquals("docs", count("SELECT COUNT(*) FROM docs t", NO_LOOKUPS).collection());
        assertEquals("docs", count("SELECT COUNT(*) FROM collections.docs", NO_LOOKUPS)
            .collection());
        assertEquals("docs", count("SELECT COUNT(*) FROM qdrant.collections.docs t", NO_LOOKUPS)
            .collection());
    }

    @Test
    void plansTheLimitACountGives() throws SQLException {
        assertEquals(5L, count("SELECT COUNT(*) FROM docs LIMIT 5", NO_LOOKUPS).limit());
        assertEquals(0L, count("SELECT COUNT(*) FROM docs LIMIT 0", NO_LOOKUPS).limit());
    }

    @Test
    void rejectsACountOfAColumn() {
        SQLException error = assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("SELECT COUNT(price) FROM docs", CATALOG));

        assertEquals("COUNT takes a star, as COUNT(*): Qdrant counts the points of a collection, "
            + "not the values of a field", error.getMessage());
    }

    @Test
    void rejectsAggregatesQdrantCantCompute() {
        for (String aggregate : List.of("SUM(price)", "AVG(price)", "MIN(price)", "MAX(price)")) {
            SQLException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT " + aggregate + " FROM docs", CATALOG), aggregate);

            assertTrue(error.getMessage().endsWith("isn't an aggregate this driver has: Qdrant "
                + "counts points, through COUNT(*), and computes no other aggregate over a "
                + "collection"), error.getMessage());
        }
    }

    @Test
    void rejectsACountAmongOtherSelectItems() {
        for (String items : List.of("COUNT(*), title", "title, COUNT(*)", "COUNT(*), COUNT(*)")) {
            SQLException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SELECT " + items + " FROM docs", CATALOG), items);

            assertEquals("COUNT(*) is a row of its own: a statement that counts a collection's "
                + "points selects nothing besides it, unless it groups them with GROUP BY",
                error.getMessage());
        }
    }

    @Test
    void rejectsACountOfASystemTable() {
        SQLException error = assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("SELECT COUNT(*) FROM system.collections", NO_LOOKUPS));

        assertEquals("Counting system.collections isn't supported: Qdrant counts the points of a "
            + "collection, and a table of the system schema describes the server instead",
            error.getMessage());

        // The table's name is still what's reported when there's no such table
        assertEquals("42S02", assertThrows(SQLSyntaxErrorException.class,
            () -> plan("SELECT COUNT(*) FROM system.queries", NO_LOOKUPS)).getSQLState());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT COUNT(*) FROM docs ORDER BY price",
        "SELECT COUNT(*) FROM docs ORDER BY similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC"})
    void rejectsWhatACountCantDoYet(String sql) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> plan(sql, NO_LOOKUPS));
    }

    // Table aliases and qualified names

    @Test
    void readsAStarNamedUnderTheTable() throws SQLException {
        // What DataGrip's data editor sends when a collection is opened
        ScrollPlan plan = scroll("SELECT t.* FROM collections.docs t");

        assertEquals(List.of("_id", "_payload", "title", "price"), names(plan));
        assertEquals("docs", plan.collection());
        assertTrue(plan.withPayload());
        assertFalse(plan.withVector());
    }

    @Test
    void readsColumnsNamedUnderTheTable() throws SQLException {
        assertEquals(List.of("title"), names(scroll("SELECT t.title FROM docs t")));
        assertEquals(List.of("title"), names(scroll("SELECT t.title FROM docs AS t")));
        assertEquals(List.of("name"), names(scroll("SELECT t.title AS name FROM docs t")));
        assertEquals("title", scroll("SELECT t.title AS name FROM docs t").columns().get(0).source());
    }

    @Test
    void readsColumnsNamedUnderTheCollectionItself() throws SQLException {
        assertEquals(List.of("title"), names(scroll("SELECT docs.title FROM docs")));
        assertEquals(List.of("_id", "_payload", "title", "price"),
                names(scroll("SELECT docs.* FROM docs")));
    }

    @Test
    void expandsAStarAmongOtherColumns() throws SQLException {
        assertEquals(List.of("_id", "_payload", "title", "price", "title"),
                names(scroll("SELECT t.*, t.title FROM docs t")));
        assertEquals(List.of("price", "_id", "_payload", "title", "price"),
                names(scroll("SELECT price, * FROM docs")));
    }

    @Test
    void rejectsTheCollectionsOwnNameWhenTheStatementRenamesIt() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT docs.title FROM docs t", CATALOG));

        assertEquals("Unknown table or alias 'docs' in 'docs.title': this statement reads docs "
                + "as t", error.getMessage());
        assertEquals("42S02", error.getSQLState());
    }

    @Test
    void rejectsAQualifierThatNamesNoTable() {
        SQLSyntaxErrorException column = assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT x.title FROM docs t", CATALOG));
        assertEquals("Unknown table or alias 'x' in 'x.title': this statement reads docs as t",
                column.getMessage());
        assertEquals("42S02", column.getSQLState());

        SQLSyntaxErrorException star = assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT x.* FROM docs", CATALOG));
        assertEquals("Unknown table or alias 'x' in 'x.*': this statement reads docs",
                star.getMessage());
    }

    @Test
    void readsANestedPayloadFieldWrittenWithoutQuotes() throws SQLException {
        assertEquals(List.of("specs.color"), names(scroll("SELECT specs.color FROM products")));
        assertEquals(List.of("specs.color"),
                names(scroll("SELECT t.\"specs.color\" FROM products t")));
    }

    @Test
    void reportsAMisspelledNestedPayloadFieldAsAnUnknownColumn() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT specs.colour FROM products", CATALOG));

        assertEquals("Unknown column 'specs.colour' in products", error.getMessage());
        assertEquals("42S22", error.getSQLState());
    }

    @Test
    void reportsAnUnknownColumnUnderTheNameTheStatementWrote() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT t.pric FROM docs t", CATALOG));

        assertEquals("Unknown column 't.pric' in docs", error.getMessage());
        assertEquals("42S22", error.getSQLState());
    }

    @Test
    void rejectsAStarWithoutFromHoweverItIsNamed() {
        assertEquals("SELECT t.* needs a FROM", assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT t.*", NO_LOOKUPS)).getMessage());
    }

    // System tables

    @Test
    void plansASystemTableWithoutLookingUpCollections() throws SQLException {
        SystemTablePlan plan = system("SELECT * FROM system.collections");

        assertEquals(SystemTable.COLLECTIONS, plan.table());
        assertEquals(SystemTable.COLLECTIONS.columns(), plan.columns());
        assertEquals(null, plan.limit());
    }

    @Test
    void plansTheColumnsASystemTableSelectNames() throws SQLException {
        SystemTablePlan plan = system("SELECT points_count, name AS collection "
            + "FROM system.collections LIMIT 3");

        assertEquals(List.of("points_count", "collection"),
            plan.columns().stream().map(SystemColumn::name).toList());
        assertEquals(List.of("points_count", "name"),
            plan.columns().stream().map(SystemColumn::source).toList());
        assertEquals(3L, plan.limit());
    }

    @Test
    void readsTheSystemSchemaAndItsTablesInAnyCase() throws SQLException {
        assertEquals(SystemTable.ALIASES, system("SELECT * FROM SYSTEM.ALIASES").table());
        assertEquals(SystemTable.ALIASES,
            system("SELECT ALIAS_NAME FROM qdrant.System.Aliases").table());
    }

    @Test
    void plansACollectionNamedInTheCollectionsSchema() throws SQLException {
        assertEquals("docs", ((ScrollPlan) plan("SELECT * FROM collections.docs", CATALOG))
            .collection());
        assertEquals("docs", ((ScrollPlan) plan("SELECT * FROM qdrant.collections.docs", CATALOG))
            .collection());
    }

    @Test
    void readsASystemTableUnderTheNameAStatementGivesIt() throws SQLException {
        assertEquals(SystemTable.COLLECTIONS.columns(),
                system("SELECT t.* FROM system.collections t").columns());
        assertEquals(List.of("name"),
                system("SELECT c.name FROM system.collections c").columns().stream()
                        .map(SystemColumn::name).toList());
        assertEquals(List.of("name"),
                system("SELECT COLLECTIONS.name FROM system.collections").columns().stream()
                        .map(SystemColumn::name).toList());
    }

    @Test
    void rejectsAQualifierThatNamesNoSystemTable() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SELECT x.name FROM system.collections c", NO_LOOKUPS));

        assertEquals("Unknown table or alias 'x' in 'x.name': this statement reads "
                + "system.collections as c", error.getMessage());
        assertEquals("42S02", error.getSQLState());
    }

    @Test
    void rejectsAnUnknownSystemTable() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
            () -> plan("SELECT * FROM system.queries", NO_LOOKUPS));

        assertEquals("Unknown table 'system.queries': the system schema has aliases, cluster, "
            + "collections, health, issues, metrics, peers, shard_keys, shards, snapshots, telemetry",
            error.getMessage());
        assertEquals("42S02", error.getSQLState());
    }

    @Test
    void rejectsAnUnknownColumnOfASystemTable() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
            () -> plan("SELECT points FROM system.collections", NO_LOOKUPS));

        assertEquals("Unknown column 'points' in system.collections", error.getMessage());
        assertEquals("42S22", error.getSQLState());
    }

    @Test
    void rejectsAnUnknownSchema() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
            () -> plan("SELECT * FROM public.docs", NO_LOOKUPS));

        assertEquals("42S02", error.getSQLState());
        assertTrue(error.getMessage().startsWith("Unknown schema 'public'"), error.getMessage());
    }

    @Test
    void rejectsAnUnknownCatalog() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
            () -> plan("SELECT * FROM other.system.collections", NO_LOOKUPS));

        assertEquals("Unknown catalog 'other': this driver has only qdrant", error.getMessage());
        assertEquals("42S02", error.getSQLState());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM system.collections WHERE status = 'green'",
            "SELECT * FROM system.collections ORDER BY name",
            "SELECT 1 FROM system.collections"})
    void rejectsWhatASystemTableSelectCantDoYet(String sql) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> plan(sql, NO_LOOKUPS));
    }

    // Helpers

    private SystemTablePlan system(String sql) throws SQLException {
        return (SystemTablePlan) plan(sql, NO_LOOKUPS);
    }

    private Plan plan(String sql, Catalog catalog) throws SQLException {
        return planner.plan(Parser.parse(sql), catalog);
    }

    private ScrollPlan scroll(String sql) throws SQLException {
        return (ScrollPlan) plan(sql, CATALOG);
    }

    private FacetPlan facet(String sql) throws SQLException {
        return (FacetPlan) plan(sql, CATALOG);
    }

    private static List<String> names(FacetPlan plan) {
        return plan.columns().stream().map(FacetColumn::name).toList();
    }

    private static List<ColumnType> types(FacetPlan plan) {
        return plan.columns().stream().map(FacetColumn::type).toList();
    }

    private static List<Boolean> counting(FacetPlan plan) {
        return plan.columns().stream().map(FacetColumn::counting).toList();
    }

    private QueryPlan query(String sql) throws SQLException {
        return (QueryPlan) plan(sql, CATALOG);
    }

    private CountPlan count(String sql, Catalog catalog) throws SQLException {
        return (CountPlan) plan(sql, catalog);
    }

    private static List<String> names(ScrollPlan plan) {
        return names(plan.columns());
    }

    private static List<String> names(List<ColumnDefinition> columns) {
        return columns.stream().map(ColumnDefinition::name).toList();
    }
}
