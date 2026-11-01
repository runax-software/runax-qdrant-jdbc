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

    private static final Catalog CATALOG = name -> {
        if (name.equals("docs")) {
            return DOCS;
        }
        if (name.equals("products")) {
            return PRODUCTS;
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
            () -> plan("SELECT * FROM system.telemetry", NO_LOOKUPS));

        assertEquals("Unknown table 'system.telemetry': the system schema has aliases, "
            + "collections", error.getMessage());
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
            "SELECT * FROM system.collections OFFSET 5",
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

    private static List<String> names(ScrollPlan plan) {
        return plan.columns().stream().map(ColumnDefinition::name).toList();
    }
}
