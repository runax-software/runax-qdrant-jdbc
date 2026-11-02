package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdatePointsPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

class UpdatePlannerTest {

    private static final TableSchema DOCS = new TableSchema(
            new QdrantCollection("docs", 3, List.of(new VectorField("", VectorKind.DENSE, 2, "Cosine")), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("_payload", ColumnKind.PAYLOAD, ColumnType.JSON, 0, null, ""),
                    new ColumnDefinition("lang", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "lang", ""),
                    new ColumnDefinition("year", ColumnKind.PAYLOAD_FIELD, ColumnType.INTEGER, 0, "year", ""),
                    new ColumnDefinition("price", ColumnKind.PAYLOAD_FIELD, ColumnType.FLOAT, 0, "price", ""),
                    new ColumnDefinition("active", ColumnKind.PAYLOAD_FIELD, ColumnType.BOOLEAN, 0, "active", ""),
                    new ColumnDefinition("tags", ColumnKind.PAYLOAD_FIELD, ColumnType.JSON, 0, "tags", ""),
                    new ColumnDefinition("specs.color", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "specs.color", ""),
                    new ColumnDefinition("_vector", ColumnKind.VECTOR, ColumnType.VECTOR, 2, "", "")));

    private static final String BY_ID = "{\"must\":[{\"has_id\":[7]}]}";

    private final Planner planner = new Planner();

    @Test
    void setsFieldsByIdInOneOperation() throws SQLException {
        UpdatePointsPlan plan = update("UPDATE docs SET lang = 'de', year = 2024 WHERE _id = 7");

        assertEquals("docs", plan.collection());
        assertEquals(BY_ID, plan.filter().toString());
        assertEquals(List.of("{\"set_payload\":{\"payload\":{\"lang\":\"de\",\"year\":2024},"
                + "\"filter\":" + BY_ID + "}}"), operations(plan));
    }

    @Test
    void readsAPointIdGivenAsText() throws SQLException {
        assertEquals(BY_ID, update("UPDATE docs SET lang = 'de' WHERE _id = '7'").filter().toString(),
                "the data editor sends the ID column's text");
    }

    @Test
    void readsCellTextAsTheColumnsType() throws SQLException {
        assertEquals(List.of("{\"set_payload\":{\"payload\":{\"year\":2025,\"price\":19.5,"
                        + "\"active\":false,\"tags\":[\"a\",\"b\"]},\"filter\":" + BY_ID + "}}"),
                operations(update("UPDATE docs SET year = '2025', price = '19.5', active = 'false', "
                        + "tags = '[\"a\", \"b\"]' WHERE _id = 7")));
    }

    @Test
    void setsAFieldInsideAnObjectUnderItsKey() throws SQLException {
        assertEquals(List.of("{\"set_payload\":{\"payload\":{\"color\":\"red\"},\"key\":\"specs\","
                        + "\"filter\":" + BY_ID + "}}"),
                operations(update("UPDATE docs SET specs.color = 'red' WHERE _id = 7")));
    }

    @Test
    void deletesAFieldSetToNull() throws SQLException {
        assertEquals(List.of(
                        "{\"set_payload\":{\"payload\":{\"lang\":\"de\"},\"filter\":" + BY_ID + "}}",
                        "{\"delete_payload\":{\"keys\":[\"year\",\"specs.color\"],\"filter\":" + BY_ID + "}}"),
                operations(update("UPDATE docs SET year = NULL, lang = 'de', \"specs.color\" = NULL "
                        + "WHERE _id = 7")));
    }

    @Test
    void replacesTheWholePayloadFirst() throws SQLException {
        assertEquals(List.of(
                        "{\"overwrite_payload\":{\"payload\":{\"a\":1},\"filter\":" + BY_ID + "}}",
                        "{\"set_payload\":{\"payload\":{\"lang\":\"de\"},\"filter\":" + BY_ID + "}}"),
                operations(update("UPDATE docs SET lang = 'de', _payload = '{\"a\": 1}' WHERE _id = 7")));
        assertEquals(List.of("{\"clear_payload\":{\"filter\":" + BY_ID + "}}"),
                operations(update("UPDATE docs SET _payload = NULL WHERE _id = 7")));
    }

    @Test
    void addsAFieldTheCollectionDoesntHaveYet() throws SQLException {
        assertEquals(List.of("{\"set_payload\":{\"payload\":{\"rating\":5},\"filter\":"
                        + "{\"must\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}}]}}}"),
                operations(update("UPDATE docs t SET t.rating = 5 WHERE t.lang = 'en'")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE docs SET year = 'soon' WHERE _id = 7",
            "UPDATE docs SET price = 'cheap' WHERE _id = 7",
            "UPDATE docs SET active = 'yes' WHERE _id = 7",
            "UPDATE docs SET tags = 'not json' WHERE _id = 7",
            "UPDATE docs SET year = 2.5 WHERE _id = 7",
            "UPDATE docs SET _payload = '[1]' WHERE _id = 7",
            // A vector takes a vector of its own dimensions, not a number or other text
            "UPDATE docs SET _vector = 1 WHERE _id = 7",
            "UPDATE docs SET _vector = 'not a vector' WHERE _id = 7",
            "UPDATE docs SET _vector = '[0.1]' WHERE _id = 7",
            "UPDATE docs SET _vector = [0.1] WHERE _id = 7"})
    void rejectsValuesTheColumnCantHold(String sql) {
        assertEquals("22018", assertThrows(SQLException.class, () -> update(sql)).getSQLState(), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE docs SET lang = 'de'",
            "UPDATE docs SET _id = 8 WHERE _id = 7",
            "UPDATE system.collections SET name = 'x' WHERE name = 'y'"})
    void rejectsWhatAnUpdateCantChange(String sql) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> update(sql), sql);
    }

    // Vectors

    /**
     * A vector a statement writes isn't a ready operation: Qdrant's {@code update_vectors} takes
     * point IDs, which only the executor can read, so the plan carries the vector itself and no
     * payload operation at all.
     */
    @Test
    void carriesAVectorToWriteRatherThanAnOperation() throws SQLException {
        UpdatePointsPlan plan = update("UPDATE docs SET _vector = '[0.1, 0.2]' WHERE _id = 7");

        assertEquals(List.of(), operations(plan));
        assertEquals(Map.of("", "[0.1,0.2]"), vectors(plan));
        assertEquals(List.of(), plan.cleared());
        assertTrue(plan.writesVectors());
    }

    @Test
    void readsAVectorWrittenOutAsWellAsOneGivenAsText() throws SQLException {
        assertEquals(Map.of("", "[0.1,0.2]"),
                vectors(update("UPDATE docs SET _vector = [0.1, 0.2] WHERE _id = 7")));
    }

    /**
     * Clearing a vector needs no IDs, since {@code delete_vectors} takes the filter as it is.
     */
    @Test
    void clearsAVectorSetToNull() throws SQLException {
        UpdatePointsPlan plan = update("UPDATE docs SET _vector = NULL WHERE _id = 7");

        assertEquals(List.of(""), plan.cleared());
        assertEquals(Map.of(), vectors(plan));
        assertFalse(plan.writesVectors());
    }

    @Test
    void setsAFieldAndAVectorInTheSameStatement() throws SQLException {
        UpdatePointsPlan plan =
                update("UPDATE docs SET lang = 'de', _vector = '[0.1, 0.2]' WHERE _id = 7");

        assertEquals(List.of("{\"set_payload\":{\"payload\":{\"lang\":\"de\"},\"filter\":"
                + BY_ID + "}}"), operations(plan));
        assertEquals(Map.of("", "[0.1,0.2]"), vectors(plan));
    }

    /**
     * The driver embeds a provider's model itself; Qdrant embeds its own as it stores the point,
     * so that text travels with the vector instead.
     */
    @Test
    void embedsTextGivenForAVector() throws SQLException {
        UpdatePointsPlan driverSide =
                update("UPDATE docs SET _vector = embed('refund policy', 'openai:small-2') "
                        + "WHERE _id = 7");

        assertEquals(Map.of(), vectors(driverSide));
        assertEquals(Set.of(""), driverSide.embeds().keySet());
        assertEquals("refund policy", driverSide.embeds().get("").text());
        assertTrue(driverSide.writesVectors());

        UpdatePointsPlan serverSide =
                update("UPDATE docs SET _vector = embed('refund policy', 'qdrant:qdrant/bm25') "
                        + "WHERE _id = 7");

        assertEquals(Map.of("", "{\"text\":\"refund policy\",\"model\":\"qdrant/bm25\"}"),
                vectors(serverSide));
        assertEquals(Set.of(), serverSide.embeds().keySet());
    }

    @Test
    void rejectsAColumnSetTwiceAndTheDriversOwnNames() {
        assertEquals("42000", assertThrows(SQLException.class,
                () -> update("UPDATE docs SET lang = 'a', lang = 'b' WHERE _id = 7")).getSQLState());
        assertEquals("42000", assertThrows(SQLException.class,
                () -> update("UPDATE docs SET _rank = 1 WHERE _id = 7")).getSQLState());
    }

    private UpdatePointsPlan update(String sql) throws SQLException {
        return (UpdatePointsPlan) planner.plan(Parser.parse(sql), name -> DOCS);
    }

    private static List<String> operations(UpdatePointsPlan plan) {
        return plan.operations().stream().map(Object::toString).toList();
    }

    /** The vectors the plan writes, as JSON text, by Qdrant's name for each. */
    private static Map<String, String> vectors(UpdatePointsPlan plan) {
        return plan.vectors().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, x -> x.getValue().toString()));
    }
}
