package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateCollectionPlan.PayloadIndex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

class CreatePlannerTest {

    // Proves a CREATE TABLE doesn't look up the collection it creates
    private static final Catalog NO_LOOKUPS = name -> {
        throw new AssertionError("Looked up " + name);
    };

    private final Planner planner = new Planner();

    @Test
    void createsAnUnnamedVector() throws SQLException {
        CreateCollectionPlan plan = create("CREATE TABLE docs (_vector VECTOR(4) DISTANCE DOT)");

        assertEquals("docs", plan.collection());
        assertEquals("{\"vectors\":{\"size\":4,\"distance\":\"Dot\"}}", plan.config().toString());
        assertEquals(List.of(), plan.indexes());
    }

    @Test
    void createsNamedDenseMultiAndSparseVectors() throws SQLException {
        CreateCollectionPlan plan = create("CREATE TABLE docs (dense VECTOR(384), "
                + "colbert MULTIVECTOR(128) DISTANCE COSINE, keywords SPARSE_VECTOR MODIFIER IDF, "
                + "plain SPARSE_VECTOR, image VECTOR(512) DISTANCE EUCLID)");

        assertEquals("{\"vectors\":{"
                        + "\"dense\":{\"size\":384,\"distance\":\"Cosine\"},"
                        + "\"colbert\":{\"size\":128,\"distance\":\"Cosine\","
                        + "\"multivector_config\":{\"comparator\":\"max_sim\"}},"
                        + "\"image\":{\"size\":512,\"distance\":\"Euclid\"}},"
                        + "\"sparse_vectors\":{\"keywords\":{\"modifier\":\"idf\"},\"plain\":{}}}",
                plan.config().toString());
    }

    @Test
    void createsPayloadIndexesUnderQdrantsOrSqlsTypeNames() throws SQLException {
        CreateCollectionPlan plan = create("CREATE TABLE docs (dense VECTOR(2), "
                + "lang KEYWORD INDEX, year BIGINT INDEX, price DOUBLE INDEX, active BOOLEAN INDEX, "
                + "created TIMESTAMP INDEX, title TEXT INDEX, \"specs.color\" VARCHAR INDEX)");

        assertEquals(List.of(new PayloadIndex("lang", "keyword"), new PayloadIndex("year", "integer"),
                new PayloadIndex("price", "float"), new PayloadIndex("active", "bool"),
                new PayloadIndex("created", "datetime"), new PayloadIndex("title", "text"),
                new PayloadIndex("specs.color", "keyword")), plan.indexes());
    }

    @Test
    void createsACollectionOfIndexesOnly() throws SQLException {
        CreateCollectionPlan plan = create("CREATE TABLE IF NOT EXISTS collections.docs "
                + "(lang KEYWORD INDEX)");

        assertTrue(plan.ifNotExists());
        assertEquals("{}", plan.config().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE docs (dense VECTOR)",
            "CREATE TABLE docs (dense VECTOR(0))",
            "CREATE TABLE docs (dense VECTOR(4) DISTANCE NEAR)",
            "CREATE TABLE docs (dense VECTOR(4) INDEX)",
            "CREATE TABLE docs (dense VECTOR(4), dense VECTOR(8))",
            "CREATE TABLE docs (keywords SPARSE_VECTOR(10))",
            "CREATE TABLE docs (keywords SPARSE_VECTOR MODIFIER BM25)",
            "CREATE TABLE docs (lang KEYWORD INDEX DISTANCE COSINE)",
            "CREATE TABLE docs (_lang KEYWORD INDEX)"})
    void rejectsColumnsQdrantCantCreate(String sql) {
        assertEquals("42000", assertThrows(SQLException.class, () -> create(sql)).getSQLState(), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE docs (lang KEYWORD)",
            "CREATE TABLE docs (lang STRING INDEX)",
            "CREATE TABLE docs (_vector VECTOR(4), dense VECTOR(4))",
            "CREATE TABLE docs (_vector SPARSE_VECTOR)",
            "CREATE TABLE system.things (lang KEYWORD INDEX)"})
    void rejectsWhatQdrantHasNoWordFor(String sql) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> create(sql), sql);
    }

    @Test
    void explainsThatPayloadFieldsNeedNoDeclaring() {
        assertEquals("Payload fields need no declaring, since Qdrant's payload has no schema: "
                        + "write 'lang KEYWORD INDEX' to index it, or leave it out",
                assertThrows(SQLException.class,
                        () -> create("CREATE TABLE docs (lang KEYWORD)")).getMessage());
    }

    private CreateCollectionPlan create(String sql) throws SQLException {
        return (CreateCollectionPlan) planner.plan(Parser.parse(sql), NO_LOOKUPS);
    }
}
