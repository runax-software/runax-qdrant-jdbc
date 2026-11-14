package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.MatrixColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.MatrixPlan;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;

class MatrixPlannerTest {

    private static final TableSchema DOCS = new TableSchema(
        new QdrantCollection("docs", 9, List.of(), Map.of()),
        List.of(
            new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
            new ColumnDefinition("lang", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "lang", ""),
            new ColumnDefinition("_vector", ColumnKind.VECTOR, ColumnType.VECTOR, 4, "", ""),
            new ColumnDefinition("dense", ColumnKind.VECTOR, ColumnType.VECTOR, 4, "dense", ""),
            new ColumnDefinition("keywords", ColumnKind.VECTOR, ColumnType.SPARSE_VECTOR, 0,
                "keywords", "")));

    private static final Catalog CATALOG = name -> {
        if (name.equals("docs")) return DOCS;

        throw new SQLException("Collection " + name + " doesn't exist", "42S02");
    };

    private final Planner planner = new Planner();

    @Test
    void asksForPairsWhenTheStatementNamesNoPosition() throws SQLException {
        MatrixPlan plan = plan("SELECT * FROM distance_matrix(docs, dense, 100, 5)");

        assertEquals("docs", plan.request().collection());
        assertEquals("dense", plan.request().vector());
        assertEquals(100L, plan.request().sample());
        assertEquals(5L, plan.request().neighbours());
        assertNull(plan.request().filter());
        assertFalse(plan.request().offsets());
        assertEquals(List.of(MatrixColumn.A, MatrixColumn.B, MatrixColumn.SCORE), plan.columns());
        assertEquals(List.of("_a", "_b", "_score"), plan.names());
        assertNull(plan.limit());
        assertEquals(0, plan.offset());
        assertTrue(plan.returnsRows());
        assertFalse(plan.changesData());
    }

    @Test
    void asksForOffsetsWhenTheStatementNamesAPosition() throws SQLException {
        MatrixPlan plan = plan("SELECT m._row, m._col AS neighbour, _score, _a FROM "
            + "distance_matrix(collections.docs, _vector) m LIMIT 20 OFFSET 4");

        assertTrue(plan.request().offsets());
        assertEquals("", plan.request().vector(), "the unnamed vector goes without a name");
        assertNull(plan.request().sample(), "Qdrant's own number");
        assertNull(plan.request().neighbours());
        assertEquals(List.of(MatrixColumn.ROW, MatrixColumn.COL, MatrixColumn.SCORE,
            MatrixColumn.A), plan.columns());
        assertEquals(List.of("_row", "neighbour", "_score", "_a"), plan.names());
        assertEquals(20L, plan.limit());
        assertEquals(4, plan.offset());
    }

    @Test
    void readsTheWhereOverTheCollectionsColumns() throws SQLException {
        assertEquals("{\"must\":[{\"key\":\"lang\",\"match\":{\"value\":\"en\"}}]}",
            plan("SELECT * FROM distance_matrix(docs, dense) WHERE lang = 'en'").request()
                .filter().toString());
        assertEquals("42S22", error("SELECT * FROM distance_matrix(docs, dense) WHERE _score > 1")
            .getSQLState());
    }

    @Test
    void comparesOnlyByAVectorThatIsDense() {
        SQLException missing = error("SELECT * FROM distance_matrix(docs, lang)");

        assertEquals("42S22", missing.getSQLState());
        assertEquals("docs has no vector 'lang': distance_matrix(collection, vector, ...) compares "
            + "the points by one of the collection's vector columns", missing.getMessage());
        assertEquals("A distance matrix of sparse vector 'keywords' isn't supported: Qdrant "
                + "answers one with no pairs at all. Name a dense vector of docs",
            error("SELECT * FROM distance_matrix(docs, keywords)").getMessage());
        assertEquals("42S02", error("SELECT * FROM distance_matrix(nope, dense)").getSQLState());
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("SELECT * FROM distance_matrix(system.collections, dense)"));
    }

    @Test
    void checksTheSampleAndTheNeighbours() {
        assertEquals("A distance matrix samples at least 2 points, not 1: one point has no "
                + "neighbour to compare it with",
            error("SELECT * FROM distance_matrix(docs, dense, 1)").getMessage());
        assertEquals("A distance matrix finds at least 1 neighbour of each point, not 0",
            error("SELECT * FROM distance_matrix(docs, dense, 10, 0)").getMessage());
    }

    @Test
    void selectsOnlyTheMatrixsOwnColumns() {
        SQLException unknown = error("SELECT _a, lang FROM distance_matrix(docs, dense)");

        assertEquals("42S22", unknown.getSQLState());
        assertEquals("Column 'lang' isn't one a distance matrix has: its columns are _a, _b, "
            + "_score, _row, _col. To read the points themselves, select them by _id",
            unknown.getMessage());
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("SELECT COUNT(*) FROM distance_matrix(docs, dense)"));
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("SELECT 1 FROM distance_matrix(docs, dense)"));
    }

    private MatrixPlan plan(String sql) throws SQLException {
        return (MatrixPlan) planner.plan(Parser.parse(sql), CATALOG);
    }

    private SQLException error(String sql) {
        return assertThrows(SQLException.class, () -> plan(sql));
    }
}
