package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.Map;

class FilterPlannerTest {

    private static final TableSchema DOCS = new TableSchema(
            new QdrantCollection("docs", 3, List.of(
                    new VectorField("dense", VectorKind.DENSE, 2, "Cosine")), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("_payload", ColumnKind.PAYLOAD, ColumnType.JSON, 0, null, ""),
                    new ColumnDefinition("lang", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "lang", ""),
                    new ColumnDefinition("year", ColumnKind.PAYLOAD_FIELD, ColumnType.INTEGER, 0, "year", ""),
                    new ColumnDefinition("price", ColumnKind.PAYLOAD_FIELD, ColumnType.FLOAT, 0, "price", ""),
                    new ColumnDefinition("active", ColumnKind.PAYLOAD_FIELD, ColumnType.BOOLEAN, 0, "active", ""),
                    new ColumnDefinition("created", ColumnKind.PAYLOAD_FIELD, ColumnType.DATETIME, 0, "created", ""),
                    new ColumnDefinition("specs.color", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "specs.color", ""),
                    new ColumnDefinition("dense", ColumnKind.VECTOR, ColumnType.VECTOR, 2, "dense", "")));

    private static final TableSchema UNNAMED = new TableSchema(
            new QdrantCollection("unnamed", 1, List.of(
                    new VectorField("", VectorKind.DENSE, 2, "Cosine")), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("_vector", ColumnKind.VECTOR, ColumnType.VECTOR, 2, "", "")));

    private static final Catalog CATALOG = name -> {
        if (name.equals("docs")) {
            return DOCS;
        }
        if (name.equals("unnamed")) {
            return UNNAMED;
        }

        throw new SQLException("Collection " + name + " doesn't exist", "42S02");
    };

    private final Planner planner = new Planner();

    @Test
    void plansNoFilterWithoutWhere() throws SQLException {
        assertNull(scroll("SELECT * FROM docs").filter());
    }

    // Matching values

    @Test
    void matchesTextWholeNumbersAndBooleansExactly() throws SQLException {
        assertFilter("{\"must\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}}]}", "lang = 'en'");
        assertFilter("{\"must\":[{\"key\":\"year\",\"match\":{\"value\":2024}}]}", "year = 2024");
        assertFilter("{\"must\":[{\"key\":\"active\",\"match\":{\"value\":true}}]}", "active = true");
    }

    @Test
    void matchesDecimalsAndDateTimesAsARangeOfOneValue() throws SQLException {
        assertFilter("{\"must\":[{\"key\":\"price\",\"range\":{\"gte\":40,\"lte\":40}}]}",
                "price = 40");
        assertFilter("{\"must\":[{\"key\":\"year\",\"range\":{\"gte\":2.5,\"lte\":2.5}}]}",
                "year = 2.5");
        assertFilter("{\"must\":[{\"key\":\"created\",\"range\":"
                        + "{\"gte\":\"2024-03-01T10:00:00Z\",\"lte\":\"2024-03-01T10:00:00Z\"}}]}",
                "created = '2024-03-01T10:00:00Z'");
    }

    @Test
    void matchesANestedFieldByItsPath() throws SQLException {
        String filter = "{\"must\":[{\"key\":\"specs.color\",\"match\":{\"value\":\"red\"}}]}";

        assertFilter(filter, "\"specs.color\" = 'red'");
        assertFilter(filter, "specs.color = 'red'");
    }

    @Test
    void matchesAColumnNamedUnderTheTableOrItsAlias() throws SQLException {
        String filter = "{\"must\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}}]}";

        assertEquals(filter, scroll("SELECT * FROM docs WHERE docs.lang = 'en'").filter().toString());
        assertEquals(filter, scroll("SELECT t.* FROM collections.docs t WHERE t.lang = 'en'")
                .filter().toString());
    }

    // Ranges

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "price < 10   | lt",
            "price <= 10  | lte",
            "price > 10   | gt",
            "price >= 10  | gte"})
    void comparesNumbersWithARange(String condition, String bound) throws SQLException {
        assertFilter("{\"must\":[{\"key\":\"price\",\"range\":{\"" + bound + "\":10}}]}", condition);
    }

    @Test
    void comparesDateTimesWithARange() throws SQLException {
        assertFilter("{\"must\":[{\"key\":\"created\",\"range\":{\"gte\":\"2024-01-01T00:00:00Z\"}}]}",
                "created >= '2024-01-01T00:00:00Z'");
    }

    @Test
    void comparesNegativeNumbers() throws SQLException {
        assertFilter("{\"must\":[{\"key\":\"price\",\"range\":{\"gt\":-1.5}}]}", "price > -1.5");
    }

    // IN

    @Test
    void matchesAnyOfAListOfTextOrWholeNumbers() throws SQLException {
        assertFilter("{\"must\":[{\"key\":\"lang\",\"match\":{\"any\":[\"en\",\"de\"]}}]}",
                "lang IN ('en', 'de')");
        assertFilter("{\"must\":[{\"key\":\"year\",\"match\":{\"any\":[2023,2024]}}]}",
                "year IN (2023, 2024)");
    }

    @Test
    void matchesAListOfOtherValuesOneByOne() throws SQLException {
        assertFilter("{\"should\":[{\"key\":\"price\",\"range\":{\"gte\":1,\"lte\":1}},"
                        + "{\"key\":\"price\",\"range\":{\"gte\":2.5,\"lte\":2.5}}]}",
                "price IN (1, 2.5)");
    }

    @Test
    void excludesAListOnlyFromPointsThatHaveTheField() throws SQLException {
        assertFilter("{\"must_not\":[{\"key\":\"lang\",\"match\":{\"any\":[\"en\",\"de\"]}},"
                        + "{\"is_empty\":{\"key\":\"lang\"}}]}",
                "lang NOT IN ('en', 'de')");
    }

    // Point IDs

    @Test
    void matchesPointIdsWithHasId() throws SQLException {
        assertFilter("{\"must\":[{\"has_id\":[7]}]}", "_id = 7");
        assertFilter("{\"must\":[{\"has_id\":[7,\"5c56c793-69f3-4fbf-87e6-c4bf54c28c26\"]}]}",
                "_id IN (7, '5c56c793-69f3-4fbf-87e6-c4bf54c28c26')");
        assertFilter("{\"must_not\":[{\"has_id\":[7]}]}", "_id <> 7");
        assertFilter("{\"must_not\":[{\"has_id\":[1,2]}]}", "_id NOT IN (1, 2)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"_id = 'not-a-uuid'", "_id = -1", "_id = 1.5", "_id = true"})
    void rejectsValuesThatArentPointIds(String condition) {
        assertEquals("42000", assertThrows(SQLSyntaxErrorException.class,
                () -> scroll("SELECT * FROM docs WHERE " + condition)).getSQLState());
    }

    // NULL

    @Test
    void testsForNullWithIsEmpty() throws SQLException {
        assertFilter("{\"must\":[{\"is_empty\":{\"key\":\"lang\"}}]}", "lang IS NULL");
        assertFilter("{\"must_not\":[{\"is_empty\":{\"key\":\"lang\"}}]}", "lang IS NOT NULL");
        assertFilter("{\"must_not\":[{\"is_empty\":{\"key\":\"lang\"}}]}", "NOT lang IS NULL");
    }

    @Test
    void testsANamedVectorForNullWithHasVector() throws SQLException {
        assertFilter("{\"must\":[{\"has_vector\":\"dense\"}]}", "dense IS NOT NULL");
        assertFilter("{\"must_not\":[{\"has_vector\":\"dense\"}]}", "dense IS NULL");
    }

    @Test
    void rejectsNullTestsThatAreNeverTrue() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> scroll("SELECT * FROM unnamed WHERE _vector IS NULL"));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> scroll("SELECT * FROM docs WHERE _id IS NULL"));
    }

    // AND, OR and NOT

    @Test
    void mapsAndOrToMustAndShould() throws SQLException {
        assertFilter("{\"must\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}},"
                        + "{\"key\":\"year\",\"match\":{\"value\":2024}},"
                        + "{\"key\":\"active\",\"match\":{\"value\":true}}]}",
                "lang = 'en' AND year = 2024 AND active = true");
        assertFilter("{\"should\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}},"
                        + "{\"key\":\"lang\",\"match\":{\"value\":\"de\"}}]}",
                "lang = 'en' OR lang = 'de'");
    }

    @Test
    void nestsMixedAndOr() throws SQLException {
        assertFilter("{\"must\":[{\"key\":\"active\",\"match\":{\"value\":true}},"
                        + "{\"should\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}},"
                        + "{\"key\":\"price\",\"range\":{\"lt\":10}}]}]}",
                "active = true AND (lang = 'en' OR price < 10)");
    }

    @Test
    void excludesAValueOnlyFromPointsThatHaveTheField() throws SQLException {
        String filter = "{\"must_not\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}},"
                + "{\"is_empty\":{\"key\":\"lang\"}}]}";

        assertFilter(filter, "lang <> 'en'");
        assertFilter(filter, "lang != 'en'");
        assertFilter(filter, "NOT lang = 'en'");
        assertFilter("{\"must\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}}]}",
                "NOT NOT lang = 'en'");
    }

    @Test
    void pushesNotThroughAndAndOr() throws SQLException {
        assertFilter("{\"should\":["
                        + "{\"must_not\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}},{\"is_empty\":{\"key\":\"lang\"}}]},"
                        + "{\"must_not\":[{\"key\":\"price\",\"range\":{\"gt\":10}},{\"is_empty\":{\"key\":\"price\"}}]}]}",
                "NOT (lang = 'en' AND price > 10)");
        assertFilter("{\"must\":["
                        + "{\"must_not\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}},{\"is_empty\":{\"key\":\"lang\"}}]},"
                        + "{\"is_empty\":{\"key\":\"price\"}}]}",
                "NOT (lang = 'en' OR price IS NOT NULL)");
    }

    // Searches

    @Test
    void filtersASearch() throws SQLException {
        QueryPlan plan = (QueryPlan) planner.plan(Parser.parse("SELECT _id, _score FROM docs "
                + "WHERE lang = 'en' ORDER BY similarity(dense, [0.1, 0.2]) DESC LIMIT 5"), CATALOG);

        assertEquals("{\"must\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}}]}",
                plan.filter().toString());
    }

    // What Qdrant's filters can't do

    @ParameterizedTest
    @ValueSource(strings = {
            "lang > 'en'",
            "active < true",
            "price = ?",
            "_id > 5",
            "_payload = 'x'",
            "dense = 1",
            "lower(lang) = 'en'"})
    void rejectsConditionsQdrantCantRun(String condition) {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> scroll("SELECT * FROM docs WHERE " + condition));
    }

    @ParameterizedTest
    @ValueSource(strings = {"price = lang", "1 = 1", "10 > price"})
    void rejectsConditionsThatDontPutTheColumnFirstAndAConstantSecond(String condition) {
        assertThrows(SQLSyntaxErrorException.class,
                () -> scroll("SELECT * FROM docs WHERE " + condition));
    }

    @Test
    void rejectsTheScoreInWhere() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> planner.plan(Parser.parse("SELECT _id FROM docs WHERE _score > 0.5 "
                        + "ORDER BY similarity(dense, [0.1, 0.2]) DESC LIMIT 5"), CATALOG));
    }

    @Test
    void reportsUnknownColumnsSoTheStatementCanReadTheColumnsAgain() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> scroll("SELECT * FROM docs WHERE missing = 1"));

        assertEquals("42S22", error.getSQLState());
        assertEquals("Unknown column 'missing' in docs", error.getMessage());
    }

    // Helpers

    private void assertFilter(String expected, String condition) throws SQLException {
        assertEquals(expected, scroll("SELECT * FROM docs WHERE " + condition).filter().toString(),
                condition);
    }

    private ScrollPlan scroll(String sql) throws SQLException {
        return (ScrollPlan) planner.plan(Parser.parse(sql), CATALOG);
    }
}
