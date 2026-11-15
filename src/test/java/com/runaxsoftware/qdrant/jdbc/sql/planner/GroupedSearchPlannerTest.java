package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parameters;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.GroupedQueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;

class GroupedSearchPlannerTest {

    private static final TableSchema CHUNKS = new TableSchema(
        new QdrantCollection("chunks", 9, List.of(
            new VectorField("dense", VectorKind.DENSE, 2, "Cosine"),
            new VectorField("l2", VectorKind.DENSE, 2, "Euclid")), Map.of()),
        List.of(
            new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
            new ColumnDefinition("doc_id", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "doc_id", ""),
            new ColumnDefinition("year", ColumnKind.PAYLOAD_FIELD, ColumnType.INTEGER, 0, "year", ""),
            new ColumnDefinition("price", ColumnKind.PAYLOAD_FIELD, ColumnType.FLOAT, 0, "price", ""),
            new ColumnDefinition("meta.source", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0,
                "meta.source", ""),
            new ColumnDefinition("dense", ColumnKind.VECTOR, ColumnType.VECTOR, 2, "dense", ""),
            new ColumnDefinition("l2", ColumnKind.VECTOR, ColumnType.VECTOR, 2, "l2", "")));

    private static final Catalog CATALOG = name -> {
        if (name.equals("chunks")) return CHUNKS;

        throw new SQLException("Collection " + name + " doesn't exist", "42S02");
    };

    private static final String SEARCH = " ORDER BY similarity(dense, [0.1, 0.2]) DESC";

    private final Planner planner = new Planner();

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT DISTINCT ON (doc_id) _id, _score FROM chunks WHERE year > 2000" + SEARCH
            + " LIMIT 10",
        "SELECT _id, _score FROM chunks WHERE year > 2000" + SEARCH + " LIMIT 1 BY doc_id LIMIT 10",
        "SELECT t._id, t._score FROM chunks t WHERE t.year > 2000 ORDER BY "
            + "similarity(t.dense, [0.1, 0.2]) DESC LIMIT 1 BY t.doc_id LIMIT 10"})
    void keepsTheBestPointOfEachGroupHoweverItIsWritten(String sql) throws SQLException {
        GroupedQueryPlan plan = plan(sql);

        assertEquals("doc_id", plan.groupBy());
        assertEquals(1, plan.groupSize());
        assertFalse(plan.lowerIsBest());
        assertEquals("chunks", plan.search().collection());
        assertEquals("dense", plan.search().vector());
        assertEquals(10L, plan.search().limit());
        assertEquals(0, plan.search().offset());
        assertEquals("{\"must\":[{\"key\":\"year\",\"range\":{\"gt\":2000}}]}",
            plan.search().filter().toString());
        assertTrue(plan.returnsRows());
        assertFalse(plan.changesData());
    }

    @Test
    void keepsSeveralPointsOfEachGroupAndTheStatementsOwnLimitAndOffset() throws SQLException {
        GroupedQueryPlan plan = plan("SELECT _id FROM chunks" + SEARCH
            + " LIMIT 3 BY year LIMIT 20 OFFSET 5");

        assertEquals("year", plan.groupBy());
        assertEquals(3, plan.groupSize());
        assertEquals(20L, plan.search().limit());
        assertEquals(5, plan.search().offset());

        GroupedQueryPlan unbounded = plan("SELECT _id FROM chunks" + SEARCH + " LIMIT 2 BY doc_id");
        assertNull(unbounded.search().limit(), "the statement's maximum rows or the default decide");
    }

    @Test
    void groupsByANestedFieldUnderQdrantsNameForIt() throws SQLException {
        assertEquals("meta.source", plan("SELECT _id FROM chunks" + SEARCH
            + " LIMIT 1 BY \"meta.source\" LIMIT 5").groupBy());
    }

    @Test
    void knowsADistanceRanksTheLowestScoreFirst() throws SQLException {
        assertTrue(plan("SELECT DISTINCT ON (doc_id) _id FROM chunks "
            + "ORDER BY similarity(l2, [0.1, 0.2]) DESC LIMIT 5").lowerIsBest());
    }

    @Test
    void needsASearchToSayWhichPointsAreBest() {
        assertEquals("DISTINCT ON and LIMIT ... BY keep the best points of each group, and only a "
                + "search says which are best: add ORDER BY similarity(vector, [0.1, ...]) DESC. "
                + "To count a field's values instead, use GROUP BY with COUNT(*)",
            error("SELECT DISTINCT ON (doc_id) _id FROM chunks LIMIT 5").getMessage());
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("SELECT _id FROM chunks ORDER BY year LIMIT 2 BY doc_id LIMIT 5"));
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("SELECT doc_id, COUNT(*) FROM chunks GROUP BY doc_id LIMIT 2 BY doc_id"));
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("SELECT DISTINCT ON (doc_id) COUNT(*) FROM chunks"));
    }

    @Test
    void groupsOnlyByAFieldOfTextOrWholeNumbers() {
        assertEquals("Grouping a search by 'price' of chunks isn't supported: Qdrant groups by a "
                + "payload field that holds text or whole numbers, and this one is DOUBLE",
            error("SELECT _id FROM chunks" + SEARCH + " LIMIT 1 BY price LIMIT 5").getMessage());
        assertEquals("Grouping a search by '_id' of chunks isn't supported: Qdrant groups by a "
                + "payload field that holds text or whole numbers, and this one is not a payload "
                + "field",
            error("SELECT DISTINCT ON (_id) _id FROM chunks" + SEARCH + " LIMIT 5").getMessage());
        assertEquals("42S22", error("SELECT _id FROM chunks" + SEARCH
            + " LIMIT 1 BY nope LIMIT 5").getSQLState());
        assertEquals("LIMIT ... BY keeps at least one row of each group, not 0",
            error("SELECT _id FROM chunks" + SEARCH + " LIMIT 0 BY doc_id LIMIT 5").getMessage());
    }

    @Test
    void capsTheOffsetTheDriverSkipsItself() {
        SQLException error = error("SELECT _id FROM chunks" + SEARCH
            + " LIMIT 1 BY doc_id LIMIT 5 OFFSET 20000");

        assertTrue(error.getMessage().startsWith("OFFSET 20000 isn't supported: Qdrant's grouped "
            + "search has no offset, so the driver reads the rows it skips"), error.getMessage());
    }

    /**
     * The rows kept of each group can be a value given to a prepared statement too, numbered
     * before the statement's own LIMIT, and checked the same once it's bound.
     */
    @Test
    void takesTheRowsOfEachGroupAndTheLimitAsValues() throws SQLException {
        String sql = "SELECT _id FROM chunks" + SEARCH + " LIMIT ? BY doc_id LIMIT ? OFFSET ?";
        GroupedQueryPlan plan = (GroupedQueryPlan) planner.plan(Parameters.bind(Parser.parse(sql),
            index -> new Literal((long) index + 1)), CATALOG);

        assertEquals(2, plan.groupSize());
        assertEquals(3L, plan.search().limit());
        assertEquals(4, plan.search().offset());

        SQLException error = assertThrows(SQLException.class, () -> planner.plan(
            Parameters.bind(Parser.parse(sql), index -> new Literal(index == 1 ? 0L : 5L)), CATALOG));
        assertEquals("LIMIT ... BY keeps at least one row of each group, not 0", error.getMessage());
        assertEquals("LIMIT ... BY takes a whole number of 0 or more, not 'two'", assertThrows(
            SQLException.class, () -> planner.plan(Parameters.bind(Parser.parse(sql),
                index -> new Literal(index == 1 ? "two" : 5L)), CATALOG)).getMessage());
    }

    private GroupedQueryPlan plan(String sql) throws SQLException {
        Plan plan = planner.plan(Parser.parse(sql), CATALOG);

        return (GroupedQueryPlan) plan;
    }

    private SQLException error(String sql) {
        return assertThrows(SQLException.class, () -> planner.plan(Parser.parse(sql), CATALOG));
    }
}
