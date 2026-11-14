package com.runaxsoftware.qdrant.jdbc.sql.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.AddVectorPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ClearIssuesPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConflictAction;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DeletePointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropIndexPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropVectorPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.RenameViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.InsertPointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.NewPoint;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdatePointsPlan;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class WriteExecutorTest {

    private static final JsonNode FILTER = Json.object("must", Json.array(Json.object("has_id", Json.array(1))));

    private final List<String> calls = new ArrayList<>();
    private long matching = 3;
    private SQLException dropFailure;
    private boolean exists;
    private final List<JsonNode> taken = new ArrayList<>();
    private String failingIndex;
    private Map<String, PayloadIndexType> indexes = Map.of();
    private List<QdrantAlias> aliases = List.of();
    private List<JsonNode> matchingIds = List.of();
    private List<VectorField> vectors = List.of();

    private final Executor executor = new Executor(
        request -> {
            throw new AssertionError("Scrolled");
        },
        query -> {
            throw new AssertionError("Searched");
        },
        (collection, filter) -> {
            throw new AssertionError("Counted " + collection);
        },
        facet -> {
            throw new AssertionError("Counted the values of " + facet.key());
        },
        table -> {
            throw new AssertionError("Read " + table);
        },
        new Writer() {
            @Override
            public long count(String collection, JsonNode filter) {
                calls.add("count " + collection + " " + filter);
                return matching;
            }

            @Override
            public void deletePoints(String collection, JsonNode filter) {
                calls.add("delete " + collection + " " + filter);
            }

            @Override
            public List<JsonNode> existingIds(String collection, List<JsonNode> ids) {
                calls.add("existing " + collection + " " + ids);
                return ids.stream().filter(taken::contains).toList();
            }

            @Override
            public void insertPoints(String collection, List<JsonNode> points,
                                     boolean replaceExisting, JsonNode updateFilter) {
                calls.add("insert " + collection + " " + points
                    + (replaceExisting ? " replacing" : " skipping")
                    + (updateFilter == null ? "" : " where " + updateFilter));
            }

            @Override
            public void updatePoints(String collection, List<JsonNode> operations) {
                calls.add("update " + collection + " " + operations);
            }

            @Override
            public List<JsonNode> matchingIds(String collection, JsonNode filter, int limit) {
                calls.add("ids " + collection + " " + filter + " up to " + limit);
                return matchingIds;
            }

            @Override
            public void deleteCollection(String collection) throws SQLException {
                calls.add("drop " + collection);
                if (dropFailure != null) throw dropFailure;
            }

            @Override
            public boolean collectionExists(String collection) {
                calls.add("exists " + collection);
                return exists;
            }

            @Override
            public void createCollection(String collection, JsonNode config) {
                calls.add("create " + collection + " " + config);
            }

            @Override
            public void createIndex(String collection, String field, JsonNode schema)
                throws SQLException {
                calls.add("index " + collection + " " + field + " "
                    + (schema.isTextual() ? schema.asText() : schema.toString()));
                if (field.equals(failingIndex)) throw new SQLException("Qdrant: bad index", "42000");
            }

            @Override
            public Map<String, PayloadIndexType> payloadIndexes(String collection) {
                calls.add("indexes " + collection);
                return indexes;
            }

            @Override
            public void deleteIndex(String collection, String field) {
                calls.add("unindex " + collection + " " + field);
            }

            @Override
            public List<QdrantAlias> aliases() {
                calls.add("aliases");
                return aliases;
            }

            @Override
            public void changeAliases(List<JsonNode> actions) {
                calls.add("change " + actions);
            }

            @Override
            public void clearIssues() {
                calls.add("clear issues");
            }

            @Override
            public void updateCollection(String collection, JsonNode changes) {
                calls.add("alter " + collection + " " + changes);
            }

            @Override
            public List<VectorField> vectors(String collection) {
                calls.add("vectors " + collection);
                return vectors;
            }

            @Override
            public void createVector(String collection, String vector, JsonNode config) {
                calls.add("add vector " + collection + " " + vector + " " + config);
            }

            @Override
            public void deleteVector(String collection, String vector) {
                calls.add("drop vector " + collection + " " + vector);
            }
        });

    @Test
    void countsThePointsThenDeletesThem() throws SQLException {
        ExecutionResult result = executor.execute(new DeletePointsPlan("docs", FILTER), statement());

        assertFalse(result.hasResultSet());
        assertEquals(3, result.updateCount());
        assertEquals(List.of("count docs " + FILTER, "delete docs " + FILTER), calls);
    }

    @Test
    void sendsNoDeleteWhenNothingMatches() throws SQLException {
        matching = 0;

        assertEquals(0, executor.execute(new DeletePointsPlan("docs", FILTER), statement())
            .updateCount());
        assertEquals(List.of("count docs " + FILTER), calls);
    }

    @Test
    void sendsACollectionsChangedSettingsInOneCall() throws SQLException {
        JsonNode changes = Json.object("hnsw_config", Json.object("m", 16));

        ExecutionResult result = executor.execute(new UpdateCollectionPlan("docs", changes),
            statement());

        assertFalse(result.hasResultSet());
        assertEquals(0, result.updateCount());
        assertEquals(List.of("alter docs " + changes), calls);
    }

    @Test
    void addsAVectorTheCollectionDoesntHave() throws SQLException {
        vectors = List.of(new VectorField("dense", VectorKind.DENSE, 4, "Cosine"));
        JsonNode config = Json.object("dense", Json.object("size", 8, "distance", "Dot"));

        ExecutionResult result = executor.execute(
            new AddVectorPlan("docs", "image", config, false), statement());

        assertEquals(0, result.updateCount());
        assertEquals(List.of("vectors docs", "add vector docs image " + config), calls);
    }

    @Test
    void refusesAVectorNameThatsTakenUnlessIfNotExists() throws SQLException {
        vectors = List.of(new VectorField("image", VectorKind.DENSE, 4, "Cosine"));
        JsonNode config = Json.object("dense", Json.object("size", 8, "distance", "Dot"));

        SQLException error = assertThrows(SQLException.class, () -> executor.execute(
            new AddVectorPlan("docs", "image", config, false), statement()));
        assertEquals("42S21", error.getSQLState());
        assertTrue(error.getMessage().startsWith("docs already has a vector 'image'"));

        executor.execute(new AddVectorPlan("docs", "image", config, true), statement());
        assertEquals(List.of("vectors docs", "vectors docs"), calls, "nothing added");
    }

    @Test
    void dropsAVectorTheCollectionHas() throws SQLException {
        vectors = List.of(new VectorField("dense", VectorKind.DENSE, 4, "Cosine"),
            new VectorField("keywords", VectorKind.SPARSE, 0, null));

        assertEquals(0, executor.execute(new DropVectorPlan("docs", "keywords", false),
            statement()).updateCount());
        assertEquals(List.of("vectors docs", "drop vector docs keywords"), calls);
    }

    @Test
    void reportsAMissingVectorUnlessIfExists() throws SQLException {
        vectors = List.of(new VectorField("", VectorKind.DENSE, 4, "Cosine"),
            new VectorField("image", VectorKind.DENSE, 8, "Dot"));

        SQLException error = assertThrows(SQLException.class, () -> executor.execute(
            new DropVectorPlan("docs", "lang", false), statement()));
        assertEquals("42S22", error.getSQLState());
        assertEquals("docs has no vector 'lang': its named vectors are image. ALTER TABLE drops "
            + "vectors only: a payload field has no column to drop, so DROP INDEX removes its "
            + "index and UPDATE ... SET lang = NULL the field itself", error.getMessage());

        executor.execute(new DropVectorPlan("docs", "lang", true), statement());
        assertEquals(List.of("vectors docs", "vectors docs"), calls, "nothing dropped");
    }

    @Test
    void clearsTheIssuesInOneCall() throws SQLException {
        ExecutionResult result = executor.execute(new ClearIssuesPlan(), statement());

        assertFalse(result.hasResultSet());
        assertEquals(0, result.updateCount());
        assertEquals(List.of("clear issues"), calls);
    }

    @Test
    void dropsACollection() throws SQLException {
        ExecutionResult result = executor.execute(new DropCollectionPlan("docs", false), statement());

        assertEquals(0, result.updateCount());
        assertEquals(List.of("drop docs"), calls);
    }

    @Test
    void reportsAMissingCollectionUnlessIfExists() throws SQLException {
        dropFailure = new SQLException("Collection `docs` doesn't exist", "42S02");

        assertEquals("42S02", assertThrows(SQLException.class,
            () -> executor.execute(new DropCollectionPlan("docs", false), statement())).getSQLState());
        assertEquals(0, executor.execute(new DropCollectionPlan("docs", true), statement())
            .updateCount());
    }

    @Test
    void reportsOtherFailuresEvenWithIfExists() {
        dropFailure = new SQLException("Qdrant: forbidden", "28000");

        assertEquals("28000", assertThrows(SQLException.class,
            () -> executor.execute(new DropCollectionPlan("docs", true), statement())).getSQLState());
    }

    @Test
    void countsThePointsThenChangesThemInOneBatch() throws SQLException {
        List<JsonNode> operations = List.of(Json.object("set_payload",
            Json.object("payload", Json.object("lang", "de"), "filter", FILTER)));

        ExecutionResult result = executor.execute(new UpdatePointsPlan("docs", FILTER, operations),
            statement());

        assertEquals(3, result.updateCount());
        assertEquals(List.of("count docs " + FILTER, "update docs " + operations), calls);
    }

    @Test
    void sendsNoUpdateWhenNothingMatches() throws SQLException {
        matching = 0;

        assertEquals(0, executor.execute(new UpdatePointsPlan("docs", FILTER, List.of()), statement())
            .updateCount());
        assertEquals(List.of("count docs " + FILTER), calls);
    }

    /**
     * Qdrant's {@code update_vectors} takes point IDs and rejects a filter alone, so the IDs are
     * read first and the filter goes along as {@code update_filter}: a point that stops matching
     * in between is left alone rather than written to on the strength of a stale ID.
     */
    @Test
    void readsTheMatchingIdsBeforeItWritesAVector() throws SQLException {
        matchingIds = List.of(Json.node(1), Json.node(2));

        ExecutionResult result = executor.execute(new UpdatePointsPlan("docs", FILTER, List.of(),
            Map.of("dense", Json.array(0.1, 0.2)), Map.of(), List.of()), statement());

        assertEquals(3, result.updateCount());
        assertEquals(List.of(
            "count docs " + FILTER,
            "ids docs " + FILTER + " up to 10000",
            "update docs [{\"update_vectors\":{\"points\":["
                + "{\"id\":1,\"vector\":{\"dense\":[0.1,0.2]}},"
                + "{\"id\":2,\"vector\":{\"dense\":[0.1,0.2]}}],"
                + "\"update_filter\":" + FILTER + "}}]"), calls);
    }

    /**
     * The unnamed vector is the point's vector itself, not an entry under a name.
     */
    @Test
    void writesAnUnnamedVectorAsTheVectorItself() throws SQLException {
        matchingIds = List.of(Json.node(1));

        executor.execute(new UpdatePointsPlan("docs", FILTER, List.of(),
            Map.of("", Json.array(0.1, 0.2)), Map.of(), List.of()), statement());

        assertEquals("update docs [{\"update_vectors\":{\"points\":["
            + "{\"id\":1,\"vector\":[0.1,0.2]}],"
            + "\"update_filter\":" + FILTER + "}}]", calls.get(2));
    }

    /**
     * Clearing a vector needs no IDs: {@code delete_vectors} takes the filter as it is.
     */
    @Test
    void clearsAVectorByFilterWithoutReadingAnyIds() throws SQLException {
        executor.execute(new UpdatePointsPlan("docs", FILTER, List.of(), Map.of(), Map.of(),
            List.of("dense")), statement());

        assertEquals(List.of(
            "count docs " + FILTER,
            "update docs [{\"delete_vectors\":{\"filter\":" + FILTER
                + ",\"vector\":[\"dense\"]}}]"), calls);
    }

    @Test
    void sendsPayloadAndVectorChangesInOneBatch() throws SQLException {
        matchingIds = List.of(Json.node(1));
        List<JsonNode> operations = List.of(Json.object("set_payload",
            Json.object("payload", Json.object("lang", "de"), "filter", FILTER)));

        executor.execute(new UpdatePointsPlan("docs", FILTER, operations,
            Map.of("dense", Json.array(0.1, 0.2)), Map.of(), List.of("sparse")), statement());

        String batch = calls.get(calls.size() - 1);
        assertTrue(batch.contains("set_payload"), batch);
        assertTrue(batch.contains("update_vectors"), batch);
        assertTrue(batch.contains("delete_vectors"), batch);
    }

    /**
     * The one write the driver pays a read for, so it's capped like a deep OFFSET. The fake
     * answers one ID more than the cap, which is how a capped read is told from a complete one.
     */
    @Test
    void refusesToWriteAVectorToMorePointsThanItWillReadIdsFor() {
        matchingIds = new ArrayList<>();
        for (int i = 0; i <= 10_000; i++) matchingIds.add(Json.node(i));

        SQLException error = assertThrows(SQLFeatureNotSupportedException.class,
            () -> executor.execute(new UpdatePointsPlan("docs", FILTER, List.of(),
                Map.of("dense", Json.array(0.1, 0.2)), Map.of(), List.of()), statement()));

        assertTrue(error.getMessage().contains("10000"), error.getMessage());
        assertEquals(List.of("count docs " + FILTER, "ids docs " + FILTER + " up to 10000"), calls);
    }

    // INSERT

    private static NewPoint point(JsonNode id, boolean given) {
        return new NewPoint(id, given, Json.object("lang", "en"),
            Map.of("dense", Json.array(0.1, 0.2)), Map.of());
    }

    @Test
    void insertsThePointsAndReturnsTheirIds() throws SQLException {
        ExecutionResult result = executor.execute(new InsertPointsPlan("docs", List.of(
            point(Json.node(7), true), point(Json.node("5c56c793-69f3-4fbf-87e6-c4bf54c28c26"), false))),
            statement());

        assertEquals(2, result.updateCount());
        assertEquals(List.of("7", "5c56c793-69f3-4fbf-87e6-c4bf54c28c26"), result.keys());
        assertEquals(List.of("existing docs [7]",
            "insert docs [{\"id\":7,\"payload\":{\"lang\":\"en\"},\"vector\":{\"dense\":[0.1,0.2]}}, "
                + "{\"id\":\"5c56c793-69f3-4fbf-87e6-c4bf54c28c26\",\"payload\":{\"lang\":\"en\"},"
                + "\"vector\":{\"dense\":[0.1,0.2]}}] skipping"), calls,
            "only the IDs the statement gave are looked up");
    }

    /**
     * DO NOTHING is what Qdrant's insert_only mode already does to a point that's there, so the
     * IDs need no looking up: one call instead of two, and no 23505.
     */
    @Test
    void skipsTheIdLookupOnDoNothingEvenWhenAnIdIsTaken() throws SQLException {
        taken.add(Json.node(7));

        ExecutionResult result = executor.execute(new InsertPointsPlan("docs",
            List.of(point(Json.node(7), true)), ConflictAction.NOTHING, null), statement());

        assertEquals(List.of("7"), result.keys());
        assertEquals(1, calls.size(), "no lookup: " + calls);
        assertTrue(calls.get(0).endsWith(" skipping"), calls.get(0));
    }

    @Test
    void replacesAPointThatIsThereOnDoUpdate() throws SQLException {
        taken.add(Json.node(7));

        executor.execute(new InsertPointsPlan("docs", List.of(point(Json.node(7), true)),
            ConflictAction.UPDATE, null), statement());

        assertEquals(1, calls.size(), "no lookup: " + calls);
        assertTrue(calls.get(0).endsWith(" replacing"), calls.get(0));
    }

    @Test
    void sendsTheFilterThatGuardsADoUpdate() throws SQLException {
        executor.execute(new InsertPointsPlan("docs", List.of(point(Json.node(7), true)),
            ConflictAction.UPDATE, FILTER), statement());

        assertTrue(calls.get(0).endsWith(" replacing where " + FILTER), calls.get(0));
    }

    @Test
    void refusesAnIdThatIsTakenAndInsertsNothing() {
        taken.add(Json.node(7));

        SQLException error = assertThrows(SQLException.class, () -> executor.execute(
            new InsertPointsPlan("docs", List.of(point(Json.node(7), true))), statement()));

        assertEquals("23505", error.getSQLState());
        assertEquals("Point 7 is already in docs: INSERT adds new points, and UPDATE changes one. "
                + "Add ON CONFLICT (_id) DO NOTHING to skip the points that are there, or DO "
                + "UPDATE to replace them",
            error.getMessage());
        assertEquals(List.of("existing docs [7]"), calls);
    }

    @Test
    void putsTheUnnamedVectorOnItsOwn() throws SQLException {
        executor.execute(new InsertPointsPlan("docs", List.of(new NewPoint(Json.node(1), false,
            Json.object(), Map.of("", Json.array(1, 0)), Map.of()))), statement());

        assertEquals(List.of("insert docs [{\"id\":1,\"payload\":{},\"vector\":[1,0]}] skipping"), calls);
    }

    // CREATE TABLE

    private static final JsonNode CONFIG = Json.object("vectors", Json.object("size", 2, "distance", "Cosine"));

    private static CreateCollectionPlan create(boolean ifNotExists) {
        return new CreateCollectionPlan("docs", ifNotExists, CONFIG, List.of(
            new CreateCollectionPlan.PayloadIndex("lang", "keyword"),
            new CreateCollectionPlan.PayloadIndex("year", "integer")));
    }

    @Test
    void createsTheCollectionThenItsIndexes() throws SQLException {
        assertEquals(0, executor.execute(create(false), statement()).updateCount());
        assertEquals(List.of("create docs " + CONFIG, "index docs lang keyword",
            "index docs year integer"), calls);
    }

    @Test
    void createsNothingWhenTheCollectionExistsAndIfNotExists() throws SQLException {
        exists = true;

        executor.execute(create(true), statement());

        assertEquals(List.of("exists docs"), calls);
    }

    @Test
    void deletesTheCollectionAgainWhenAnIndexFails() {
        failingIndex = "year";

        assertEquals("42000", assertThrows(SQLException.class,
            () -> executor.execute(create(false), statement())).getSQLState());
        assertEquals(List.of("create docs " + CONFIG, "index docs lang keyword",
            "index docs year integer", "drop docs"), calls);
    }

    // Indexes

    @Test
    void createsAnIndexOnAFieldWithoutOne() throws SQLException {
        executor.execute(new CreateIndexPlan("docs", "lang", Json.node("keyword"), false), statement());

        assertEquals(List.of("indexes docs", "index docs lang keyword"), calls);
    }

    @Test
    void refusesToReplaceAnIndexUnlessIfNotExists() throws SQLException {
        indexes = Map.of("lang", PayloadIndexType.TEXT);

        SQLException error = assertThrows(SQLException.class, () -> executor.execute(
            new CreateIndexPlan("docs", "lang", Json.node("keyword"), false), statement()));
        assertEquals("42S11", error.getSQLState());
        assertEquals("'lang' of docs already has an index, lang_idx, of type text: drop it first "
            + "to change it", error.getMessage());

        executor.execute(new CreateIndexPlan("docs", "lang", Json.node("keyword"), true), statement());
        assertEquals(List.of("indexes docs", "indexes docs"), calls, "nothing replaced");
    }

    @Test
    void dropsTheIndexTheNameMeans() throws SQLException {
        indexes = Map.of("lang", PayloadIndexType.KEYWORD);

        executor.execute(new DropIndexPlan("docs", List.of("lang", "lang_idx"), "lang_idx", false),
            statement());

        assertEquals(List.of("indexes docs", "unindex docs lang"), calls);
    }

    @Test
    void reportsAMissingIndexUnlessIfExists() throws SQLException {
        indexes = Map.of("year", PayloadIndexType.INTEGER);

        SQLException error = assertThrows(SQLException.class, () -> executor.execute(
            new DropIndexPlan("docs", List.of("lang"), "lang", false), statement()));
        assertEquals("42S12", error.getSQLState());
        assertEquals("No index 'lang' on docs: its indexes are year_idx", error.getMessage());

        executor.execute(new DropIndexPlan("docs", List.of("lang"), "lang", true), statement());
        assertFalse(calls.stream().anyMatch(x -> x.startsWith("unindex")));
    }

    // Views

    @Test
    void createsAView() throws SQLException {
        executor.execute(new CreateViewPlan("current", "docs_v1", false), statement());

        assertEquals(List.of("aliases", "change [{\"create_alias\":{\"collection_name\":"
            + "\"docs_v1\",\"alias_name\":\"current\"}}]"), calls);
    }

    @Test
    void movesAViewOnlyWithOrReplace() throws SQLException {
        aliases = List.of(new QdrantAlias("current", "docs_v1"));

        SQLException error = assertThrows(SQLException.class,
            () -> executor.execute(new CreateViewPlan("current", "docs_v2", false), statement()));
        assertEquals("42S01", error.getSQLState());

        executor.execute(new CreateViewPlan("current", "docs_v2", true), statement());
        assertEquals("change [{\"delete_alias\":{\"alias_name\":\"current\"}}, "
            + "{\"create_alias\":{\"collection_name\":\"docs_v2\",\"alias_name\":\"current\"}}]",
            calls.get(calls.size() - 1), "both actions in one atomic change");
    }

    @Test
    void refusesAViewOfAView() {
        aliases = List.of(new QdrantAlias("current", "docs_v1"));

        assertEquals("'current' is a view of docs_v1: a view shows a collection, so name docs_v1",
            assertThrows(SQLException.class, () -> executor.execute(
                new CreateViewPlan("other", "current", false), statement())).getMessage());
    }

    @Test
    void renamesAViewToAFreeName() throws SQLException {
        aliases = List.of(new QdrantAlias("current", "docs"), new QdrantAlias("taken", "docs"));

        assertEquals("42S02", assertThrows(SQLException.class, () -> executor.execute(
            new RenameViewPlan("missing", "live"), statement())).getSQLState());
        assertEquals("42S01", assertThrows(SQLException.class, () -> executor.execute(
            new RenameViewPlan("current", "taken"), statement())).getSQLState());

        executor.execute(new RenameViewPlan("current", "live"), statement());
        assertEquals("change [{\"rename_alias\":{\"old_alias_name\":\"current\","
            + "\"new_alias_name\":\"live\"}}]", calls.get(calls.size() - 1));
    }

    @Test
    void dropsAViewThatExists() throws SQLException {
        aliases = List.of(new QdrantAlias("current", "docs"));

        assertEquals("42S02", assertThrows(SQLException.class, () -> executor.execute(
            new DropViewPlan("missing", false), statement())).getSQLState());
        executor.execute(new DropViewPlan("missing", true), statement());
        executor.execute(new DropViewPlan("current", false), statement());

        assertEquals(List.of("aliases", "aliases", "aliases",
            "change [{\"delete_alias\":{\"alias_name\":\"current\"}}]"), calls);
    }

    private static Statement statement() {
        return (Statement) Proxy.newProxyInstance(WriteExecutorTest.class.getClassLoader(),
            new Class<?>[] {Statement.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "getLargeMaxRows" -> 0L;
                case "getFetchSize" -> 0;
                default -> throw new AssertionError("Called Statement." + method.getName());
            });
    }
}
