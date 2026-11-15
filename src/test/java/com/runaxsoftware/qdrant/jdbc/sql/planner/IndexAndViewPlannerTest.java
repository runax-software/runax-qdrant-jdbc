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
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.RenameViewPlan;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;

class IndexAndViewPlannerTest {

    private static final TableSchema DOCS = new TableSchema(
            new QdrantCollection("docs", 3, List.of(), Map.of()),
            List.of(
                    new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
                    new ColumnDefinition("_payload", ColumnKind.PAYLOAD, ColumnType.JSON, 0, null, ""),
                    new ColumnDefinition("lang", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "lang", ""),
                    new ColumnDefinition("year", ColumnKind.PAYLOAD_FIELD, ColumnType.INTEGER, 0, "year", ""),
                    new ColumnDefinition("price", ColumnKind.PAYLOAD_FIELD, ColumnType.FLOAT, 0, "price", ""),
                    new ColumnDefinition("tags", ColumnKind.PAYLOAD_FIELD, ColumnType.JSON, 0, "tags", ""),
                    new ColumnDefinition("specs.color", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "specs.color", ""),
                    new ColumnDefinition("dense", ColumnKind.VECTOR, ColumnType.VECTOR, 2, "dense", "")));

    private static final Catalog CATALOG = name -> {
        if (name.equals("docs")) return DOCS;
        throw new SQLException("Collection `" + name + "` doesn't exist", "42S02");
    };

    private final Planner planner = new Planner();

    // CREATE INDEX

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "lang  | keyword",
            "year  | integer",
            "price | float"})
    void indexesAColumnAsItsTypeSays(String column, String type) throws SQLException {
        assertEquals(new CreateIndexPlan("docs", column, Json.node(type), false),
                plan("CREATE INDEX ON docs (" + column + ")"));
    }

    @Test
    void buildsATextIndexThatScores() throws SQLException {
        assertEquals("{\"type\":\"text\",\"scoring\":true}", ((CreateIndexPlan) plan(
                "CREATE INDEX ON docs (lang) USING text WITH (scoring = true)")).schema().toString());
    }

    @Test
    void indexesWithTheTypeAndOptionsGiven() throws SQLException {
        CreateIndexPlan plan = (CreateIndexPlan) plan("CREATE INDEX IF NOT EXISTS lang_idx ON docs (lang) "
                + "USING text WITH (tokenizer = word, phrase_matching = 'true', min_token_len = 2, "
                + "stemmer = english)");

        assertEquals("{\"type\":\"text\",\"tokenizer\":\"word\",\"phrase_matching\":true,"
                + "\"min_token_len\":2,\"stemmer\":{\"type\":\"snowball\",\"language\":\"english\"}}",
                plan.schema().toString());
        assertEquals(true, plan.ifNotExists());
    }

    @Test
    void indexesANestedFieldAndOneTheCollectionDoesntHaveYet() throws SQLException {
        assertEquals("specs.color", ((CreateIndexPlan) plan("CREATE INDEX ON docs (specs.color)")).field());
        assertEquals("{\"type\":\"keyword\",\"is_tenant\":true}", ((CreateIndexPlan) plan(
                "CREATE INDEX ON docs (tenant) USING keyword WITH (is_tenant = true)")).schema().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE INDEX ON docs (tags)",
            "CREATE INDEX ON docs (tenant)",
            "CREATE INDEX ON docs (lang) USING fulltext",
            "CREATE INDEX ON docs (lang) WITH (is_principal = true)",
            "CREATE INDEX ON docs (lang) USING text WITH (min_token_len = 0)",
            "CREATE INDEX ON docs (lang) USING keyword WITH (is_tenant = 'maybe')",
            "CREATE INDEX other_idx ON docs (lang)",
            "CREATE INDEX ON docs (_rank) USING keyword"})
    void rejectsIndexesQdrantWouldGetWrong(String sql) {
        assertThrows(SQLException.class, () -> plan(sql), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE INDEX ON docs (_id)",
            "CREATE INDEX ON docs (_payload)",
            "CREATE INDEX ON docs (dense)",
            "CREATE INDEX ON system.collections (name)"})
    void rejectsWhatIsntPayload(String sql) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> plan(sql), sql);
    }

    @Test
    void namesTheOptionsATypeTakes() {
        assertEquals("A keyword index has no option 'is_principal': it takes is_tenant, on_disk",
                assertThrows(SQLException.class, () -> plan(
                        "CREATE INDEX ON docs (lang) WITH (is_principal = true)")).getMessage());
    }

    @Test
    void reportsAMissingCollection() {
        assertEquals("42S02", assertThrows(SQLException.class,
                () -> plan("CREATE INDEX ON missing (lang) USING keyword")).getSQLState());
    }

    // DROP INDEX

    @Test
    void dropsAnIndexByTheNameTheDriverReportsOrByItsField() throws SQLException {
        assertEquals(new DropIndexPlan("docs", List.of("lang", "lang_idx"), "lang_idx", false),
                plan("DROP INDEX lang_idx ON docs"));
        assertEquals(new DropIndexPlan("docs", List.of("lang"), "lang", true),
                plan("DROP INDEX IF EXISTS lang ON docs"));
        assertEquals(new DropIndexPlan("docs", List.of("specs.color"), "specs.color", false),
                plan("DROP INDEX ON docs (\"specs.color\")"));
    }

    // Views

    @Test
    void makesAViewOfAWholeCollection() throws SQLException {
        assertEquals(new CreateViewPlan("current", "docs", false),
                plan("CREATE VIEW current AS SELECT * FROM docs"));
        assertEquals(new CreateViewPlan("current", "docs", true),
                plan("CREATE OR REPLACE VIEW collections.current AS SELECT t.* FROM collections.docs t"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE VIEW v AS SELECT lang FROM docs",
            "CREATE VIEW v AS SELECT * FROM docs WHERE lang = 'en'",
            "CREATE VIEW v AS SELECT * FROM docs LIMIT 5",
            "CREATE VIEW v AS SELECT * FROM docs ORDER BY year LIMIT 5",
            "CREATE VIEW v AS SELECT *, lang FROM docs",
            "CREATE VIEW v AS SELECT 1"})
    void rejectsAViewThatIsntAWholeCollection(String sql) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> plan(sql), sql);
    }

    @Test
    void rejectsAViewOfNothingOrOfItself() {
        assertEquals("42S02", assertThrows(SQLException.class,
                () -> plan("CREATE VIEW v AS SELECT * FROM missing")).getSQLState());
        assertEquals("42S01", assertThrows(SQLException.class,
                () -> plan("CREATE VIEW docs AS SELECT * FROM docs")).getSQLState());
        assertEquals("42S02", assertThrows(SQLException.class,
                () -> plan("CREATE VIEW v AS SELECT x.* FROM docs")).getSQLState());
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("CREATE VIEW system.v AS SELECT * FROM docs"));
    }

    @Test
    void renamesAndDropsViews() throws SQLException {
        assertEquals(new RenameViewPlan("current", "live"), plan("ALTER VIEW current RENAME TO live"));
        assertEquals(new DropViewPlan("current", true), plan("DROP VIEW IF EXISTS current"));
        assertEquals("42000", assertThrows(SQLException.class,
                () -> plan("ALTER VIEW current RENAME TO current")).getSQLState());
    }

    @Test
    void changesWhatQdrantHolds() throws SQLException {
        for (String sql : List.of("CREATE INDEX ON docs (lang)", "DROP INDEX lang_idx ON docs",
                "CREATE VIEW v AS SELECT * FROM docs", "ALTER VIEW v RENAME TO w", "DROP VIEW v")) {
            Plan plan = plan(sql);
            assertFalse(plan.returnsRows(), sql);
            assertEquals(true, plan.changesData(), sql);
        }
    }

    private Plan plan(String sql) throws SQLException {
        return planner.plan(Parser.parse(sql), CATALOG);
    }
}
