package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.AddVectorPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropVectorPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;

class AlterPlannerTest {

    // An ALTER TABLE says what it changes, so the collection's columns are never read to plan it
    private static final Catalog NO_LOOKUPS = name -> {
        throw new AssertionError("Looked up " + name);
    };

    private final Planner planner = new Planner();

    @Test
    void addsADenseVectorWithItsDistance() throws SQLException {
        AddVectorPlan plan = (AddVectorPlan) plan(
            "ALTER TABLE docs ADD COLUMN image VECTOR(512) DISTANCE DOT");

        assertEquals("docs", plan.collection());
        assertEquals("image", plan.vector());
        assertEquals("{\"dense\":{\"size\":512,\"distance\":\"Dot\"}}", plan.config().toString());
        assertFalse(plan.ifNotExists());
        assertFalse(plan.returnsRows());
        assertTrue(plan.changesData(), "a read-only connection refuses it");
    }

    @Test
    void addsAMultivectorAndASparseVector() throws SQLException {
        assertEquals("{\"dense\":{\"size\":128,\"distance\":\"Cosine\",\"multivector_config\":"
                + "{\"comparator\":\"max_sim\"}}}",
            ((AddVectorPlan) plan("ALTER TABLE docs ADD COLUMN colbert MULTIVECTOR(128)"))
                .config().toString());

        AddVectorPlan sparse = (AddVectorPlan) plan(
            "ALTER TABLE collections.docs ADD COLUMN IF NOT EXISTS keywords SPARSE_VECTOR "
                + "MODIFIER IDF");
        assertEquals("{\"sparse\":{\"modifier\":\"idf\"}}", sparse.config().toString());
        assertTrue(sparse.ifNotExists());

        assertEquals("{\"sparse\":{}}",
            ((AddVectorPlan) plan("ALTER TABLE docs ADD keywords SPARSE_VECTOR")).config().toString());
    }

    @Test
    void checksAnAddedVectorAsCreateTableDoes() {
        assertEquals("Vector 'image' needs its dimensions, as VECTOR(384)",
            assertThrows(SQLSyntaxErrorException.class,
                () -> plan("ALTER TABLE docs ADD COLUMN image VECTOR")).getMessage());
        assertThrows(SQLSyntaxErrorException.class,
            () -> plan("ALTER TABLE docs ADD COLUMN image VECTOR(4) DISTANCE HAMMING"));
        assertThrows(SQLSyntaxErrorException.class,
            () -> plan("ALTER TABLE docs ADD COLUMN keywords SPARSE_VECTOR(4)"));
    }

    @Test
    void pointsAPayloadColumnAtCreateIndex() {
        assertEquals("Payload fields need no adding, since Qdrant's payload has no schema: a point "
                + "has 'lang' once an INSERT or UPDATE gives it one. To index the field, CREATE "
                + "INDEX ON docs (lang) USING keyword",
            assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("ALTER TABLE docs ADD COLUMN lang VARCHAR")).getMessage());
        assertEquals("Unknown column type 'BLOB' for 'cover': ALTER TABLE adds vectors, as "
                + "VECTOR(n), MULTIVECTOR(n) or SPARSE_VECTOR",
            assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("ALTER TABLE docs ADD COLUMN cover BLOB")).getMessage());
    }

    @Test
    void dropsAVector() throws SQLException {
        assertEquals(new DropVectorPlan("docs", "image", false),
            plan("ALTER TABLE docs DROP COLUMN image"));
        assertEquals(new DropVectorPlan("docs", "image", true),
            plan("ALTER TABLE qdrant.collections.docs DROP COLUMN IF EXISTS image"));
        assertTrue(plan("ALTER TABLE docs DROP image").changesData());
    }

    @Test
    void leavesTheUnnamedVectorToCreateTable() {
        assertTrue(assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("ALTER TABLE docs ADD COLUMN _vector VECTOR(4)")).getMessage()
            .startsWith("Can't add _vector to docs"));
        assertTrue(assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("ALTER TABLE docs DROP COLUMN _vector")).getMessage()
            .startsWith("Can't drop _vector of docs: Qdrant deletes only named vectors"));
    }

    @Test
    void keepsTheSystemTablesAsTheyAre() {
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("ALTER TABLE system.collections ADD COLUMN image VECTOR(4)"));
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("ALTER TABLE system.issues DROP COLUMN id"));
    }

    private Plan plan(String sql) throws SQLException {
        return planner.plan(Parser.parse(sql), NO_LOOKUPS);
    }
}
