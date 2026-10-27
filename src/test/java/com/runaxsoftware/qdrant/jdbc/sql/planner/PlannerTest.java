package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;

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

    private static final Catalog CATALOG = name -> {
        if (name.equals("docs")) {
            return DOCS;
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
            "SELECT * FROM docs WHERE price > 1",
            "SELECT * FROM docs ORDER BY price",
            "SELECT * FROM docs OFFSET 5",
            "SELECT 1 FROM docs"})
    void rejectsWhatASelectWithFromCantDoYet(String sql) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> plan(sql, CATALOG));
    }

    // Helpers

    private Plan plan(String sql, Catalog catalog) throws SQLException {
        return planner.plan(Parser.parse(sql), catalog);
    }

    private ScrollPlan scroll(String sql) throws SQLException {
        return (ScrollPlan) plan(sql, CATALOG);
    }

    private static List<String> names(ScrollPlan plan) {
        return plan.columns().stream().map(ColumnDefinition::name).toList();
    }
}
