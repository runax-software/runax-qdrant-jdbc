package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.GroupedQueryPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.QueryPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.Map;

class TextRankPlannerTest {

    // 'title' has a text index, 'lang' a keyword one, and 'body' none; 'l2' ranks lowest first
    private static final TableSchema DOCS = new TableSchema(
        new QdrantCollection("docs", 9,
            List.of(new VectorField("l2", VectorKind.DENSE, 2, "Euclid")),
            Map.of("title", PayloadIndexType.TEXT, "meta.summary", PayloadIndexType.TEXT,
                "lang", PayloadIndexType.KEYWORD)),
        List.of(
            new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
            new ColumnDefinition("_payload", ColumnKind.PAYLOAD, ColumnType.JSON, 0, null, ""),
            new ColumnDefinition("title", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "title", ""),
            new ColumnDefinition("meta.summary", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0,
                "meta.summary", ""),
            new ColumnDefinition("body", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "body", ""),
            new ColumnDefinition("lang", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "lang", ""),
            new ColumnDefinition("l2", ColumnKind.VECTOR, ColumnType.VECTOR, 2, "l2", "")));

    private static final Catalog CATALOG = name -> {
        if (name.equals("docs")) return DOCS;

        throw new SQLException("Collection " + name + " doesn't exist", "42S02");
    };

    private final Planner planner = new Planner();

    @Test
    void ranksAPayloadFieldByTheWords() throws SQLException {
        QueryPlan plan = (QueryPlan) plan("SELECT _id, _score, title FROM docs WHERE lang = 'en' "
            + "ORDER BY text_rank(title, 'refund policy') DESC LIMIT 5 OFFSET 2");

        assertEquals("refund policy", plan.text());
        assertEquals("title", plan.vector(), "the field goes where a search names its vector");
        assertEquals(List.of(), plan.query());
        assertNull(plan.embed());
        assertEquals(5L, plan.limit());
        assertEquals(2, plan.offset());
        assertEquals("{\"must\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}}]}",
            plan.filter().toString());
        assertEquals(List.of("_id", "_score", "title"),
            plan.columns().stream().map(ColumnDefinition::name).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT _id FROM docs ORDER BY TEXT_RANK(title, 'refund') DESC LIMIT 5",
        "SELECT d._id FROM docs d ORDER BY text_rank(d.title, 'refund') DESC LIMIT 5",
        "SELECT _id FROM docs ORDER BY text_rank(docs.title, 'refund') DESC LIMIT 5"})
    void namesTheFieldHoweverItIsWritten(String sql) throws SQLException {
        assertEquals("title", ((QueryPlan) plan(sql)).vector());
    }

    @Test
    void ranksANestedField() throws SQLException {
        assertEquals("meta.summary", ((QueryPlan) plan("SELECT _id FROM docs "
            + "ORDER BY text_rank(\"meta.summary\", 'refund') DESC LIMIT 5")).vector());
    }

    @Test
    void ranksAVectorSearchWithNoText() throws SQLException {
        assertNull(((QueryPlan) plan("SELECT _id FROM docs "
            + "ORDER BY similarity(l2, [0.1, 0.2]) DESC LIMIT 5")).text());
    }

    @Test
    void keepsTheBestMatchesOfEachGroupHighestScoreFirst() throws SQLException {
        GroupedQueryPlan plan = (GroupedQueryPlan) plan("SELECT _id, _score FROM docs "
            + "ORDER BY text_rank(title, 'refund') DESC LIMIT 2 BY lang LIMIT 10");

        assertEquals("refund", plan.search().text());
        assertEquals("lang", plan.groupBy());
        assertFalse(plan.lowerIsBest(), "a relevance, whatever the collection's vectors measure");
    }

    @Test
    void returnsTheBestMatchesFirst() {
        assertEquals("A search by text returns the best matches first, so it needs ORDER BY "
                + "text_rank(...) DESC",
            unsupported("SELECT _id FROM docs ORDER BY text_rank(title, 'refund') LIMIT 5"));
    }

    @Test
    void needsATextIndexOnTheField() {
        assertEquals("Ranking by 'body' of docs needs a text index that scores, and this field "
                + "is not indexed: CREATE INDEX ON docs (body) USING text WITH (scoring = true)",
            unsupported("SELECT _id FROM docs ORDER BY text_rank(body, 'refund') DESC LIMIT 5"));
        assertEquals("Ranking by 'lang' of docs needs a text index that scores, and this field "
                + "is indexed as keyword: CREATE INDEX ON docs (lang) USING text WITH "
                + "(scoring = true)",
            unsupported("SELECT _id FROM docs ORDER BY text_rank(lang, 'en') DESC LIMIT 5"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"_id", "_payload", "l2"})
    void ranksOnlyAPayloadField(String column) {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
            () -> plan("SELECT _id FROM docs ORDER BY text_rank(" + column + ", 'refund') DESC "
                + "LIMIT 5"));

        assertEquals("Column '" + column + "' of docs isn't a payload field: text_rank ranks by "
            + "a field's text, as text_rank(title, 'refund policy')", error.getMessage());
        assertEquals("42000", error.getSQLState());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "text_rank(title)",
        "text_rank(title, 'refund', 'policy')",
        "text_rank('refund', title)",
        "text_rank()"})
    void takesAFieldAndTheWords(String call) {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
            () -> plan("SELECT _id FROM docs ORDER BY " + call + " DESC LIMIT 5"));

        assertEquals("text_rank takes a payload field and the words to rank it by, as "
            + "text_rank(title, 'refund policy')", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "[0.1, 0.2]", "lang", "embed('refund')"})
    void takesTheWordsInQuotes(String words) {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
            () -> plan("SELECT _id FROM docs ORDER BY text_rank(title, " + words + ") DESC "
                + "LIMIT 5"));

        assertEquals("text_rank takes text in quotes: text_rank(title, 'refund policy')",
            error.getMessage());
    }

    @Test
    void reportsAFieldTheCollectionDoesntHave() {
        assertEquals("42S22", assertThrows(SQLSyntaxErrorException.class,
            () -> plan("SELECT _id FROM docs ORDER BY text_rank(missing, 'refund') DESC LIMIT 5"))
            .getSQLState());
    }

    @Test
    void namesBothSearchesForAFunctionItDoesntHave() {
        assertEquals("'bm25' isn't a function this driver has; a search orders by "
                + "similarity(vector, [0.1, ...]) DESC or text_rank(field, 'words') DESC",
            unsupported("SELECT _id FROM docs ORDER BY bm25(title, 'refund') DESC LIMIT 5"));
    }

    private String unsupported(String sql) {
        return assertThrows(SQLFeatureNotSupportedException.class, () -> plan(sql)).getMessage();
    }

    private Plan plan(String sql) throws SQLException {
        return planner.plan(Parser.parse(sql), CATALOG);
    }
}
