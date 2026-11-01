package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DeletePointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;

class WritePlannerTest {

    private static final TableSchema DOCS = new TableSchema(
            new QdrantCollection("docs", 3, List.of(), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("lang", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "lang", "")));

    private static final Catalog CATALOG = name -> {
        if (name.equals("docs")) {
            return DOCS;
        }

        throw new SQLException("Collection " + name + " doesn't exist", "42S02");
    };

    // Proves a DROP TABLE doesn't read the collection's columns
    private static final Catalog NO_LOOKUPS = name -> {
        throw new AssertionError("Looked up " + name);
    };

    private final Planner planner = new Planner();

    @Test
    void deletesThePointsTheWhereMatches() throws SQLException {
        DeletePointsPlan plan = (DeletePointsPlan) plan("DELETE FROM docs WHERE lang = 'en'", CATALOG);

        assertEquals("docs", plan.collection());
        assertEquals("{\"must\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}}]}",
                plan.filter().toString());
        assertFalse(plan.returnsRows());
    }

    @Test
    void deletesUnderAnAliasAndASchema() throws SQLException {
        DeletePointsPlan plan = (DeletePointsPlan) plan(
                "DELETE FROM qdrant.collections.docs t WHERE t._id IN (1, 2)", CATALOG);

        assertEquals("{\"must\":[{\"has_id\":[1,2]}]}", plan.filter().toString());
    }

    @Test
    void rejectsADeleteWithoutWhere() {
        SQLFeatureNotSupportedException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("DELETE FROM docs", CATALOG));

        assertEquals("DELETE without WHERE would delete every point of docs: to do that, use "
                + "TRUNCATE TABLE docs", error.getMessage());
    }

    @Test
    void truncatesWithAFilterThatMatchesEveryPoint() throws SQLException {
        DeletePointsPlan plan = (DeletePointsPlan) plan("TRUNCATE TABLE docs", CATALOG);

        assertEquals("docs", plan.collection());
        assertEquals("{}", plan.filter().toString());
        assertFalse(plan.returnsRows());
    }

    @Test
    void dropsWithoutReadingTheColumns() throws SQLException {
        assertEquals(new DropCollectionPlan("docs", false), plan("DROP TABLE docs", NO_LOOKUPS));
        assertEquals(new DropCollectionPlan("docs", true),
                plan("DROP TABLE IF EXISTS collections.docs", NO_LOOKUPS));
    }

    @Test
    void reportsUnknownCollectionsAndColumns() {
        assertEquals("42S02", assertThrows(SQLException.class,
                () -> plan("DELETE FROM missing WHERE lang = 'en'", CATALOG)).getSQLState());
        assertEquals("42S22", assertThrows(SQLException.class,
                () -> plan("DELETE FROM docs WHERE missing = 1", CATALOG)).getSQLState());
        assertEquals("42S02", assertThrows(SQLException.class,
                () -> plan("DROP TABLE other.docs", NO_LOOKUPS)).getSQLState());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM system.collections WHERE name = 'docs'",
            "TRUNCATE TABLE system.aliases",
            "DROP TABLE system.collections"})
    void rejectsWritesToTheSystemTables(String sql) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> plan(sql, NO_LOOKUPS));
    }

    private Plan plan(String sql, Catalog catalog) throws SQLException {
        return planner.plan(Parser.parse(sql), catalog);
    }
}
