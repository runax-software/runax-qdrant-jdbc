package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOption;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.SetOptionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ShowOptionsPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.Arrays;
import java.util.List;

class OptionPlannerTest {

    // Proves the option statements never look a collection up
    private static final Catalog NO_LOOKUPS = name -> {
        throw new AssertionError("Looked up " + name);
    };

    private final Planner planner = new Planner();

    @ParameterizedTest
    @ValueSource(strings = {
            "SET qdrant.consistency = 'majority'",
            "SET qdrant.consistency TO majority",
            "set QDRANT.Consistency = 'MAJORITY';"})
    void setsAnOptionWrittenAnyWay(String sql) throws SQLException {
        assertEquals(new SetOptionPlan(RequestOption.CONSISTENCY, "majority"), plan(sql));
    }

    @Test
    void readsEachOptionsValue() throws SQLException {
        assertEquals(new SetOptionPlan(RequestOption.TIMEOUT, 30), plan("SET qdrant.timeout = 30"));
        assertEquals(new SetOptionPlan(RequestOption.WAIT, false), plan("SET qdrant.wait = false"));
        assertEquals(new SetOptionPlan(RequestOption.HNSW_EF, 128), plan("SET qdrant.hnsw_ef = '128'"));
        assertEquals(new SetOptionPlan(RequestOption.CONSISTENCY, 2), plan("SET qdrant.consistency = 2"));
    }

    @Test
    void resetsOneOptionOrAll() throws SQLException {
        assertEquals(new SetOptionPlan(RequestOption.ORDERING, null), plan("SET qdrant.ordering = DEFAULT"));
        assertEquals(new SetOptionPlan(RequestOption.ORDERING, null), plan("RESET qdrant.ordering"));
        assertEquals(new SetOptionPlan(null, null), plan("reset all"));
    }

    @Test
    void showsOneOptionOrAll() throws SQLException {
        assertEquals(new ShowOptionsPlan(List.of(RequestOption.EXACT)), plan("SHOW qdrant.exact"));
        assertEquals(new ShowOptionsPlan(Arrays.asList(RequestOption.values())), plan("SHOW ALL"));
    }

    @Test
    void changesTheConnectionNotQdrant() throws SQLException {
        Plan set = plan("SET qdrant.wait = false");

        assertFalse(set.returnsRows());
        assertFalse(set.changesData(), "so a read-only connection runs it");
    }

    @Test
    void rejectsSettingsOfOtherDatabases() {
        SQLFeatureNotSupportedException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> plan("SET search_path = public"));

        assertEquals("'search_path' isn't an option this driver has: it has qdrant.consistency, "
                + "qdrant.ordering, qdrant.wait, qdrant.timeout, qdrant.hnsw_ef, qdrant.exact, "
                + "qdrant.indexed_only", error.getMessage());
    }

    @Test
    void rejectsUnknownOptionsAndValues() {
        assertEquals("42704", assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SET qdrant.speed = 'fast'")).getSQLState());
        assertEquals("22023", assertThrows(SQLException.class,
                () -> plan("SET qdrant.ordering = 'fast'")).getSQLState());
        assertEquals("42704", assertThrows(SQLSyntaxErrorException.class,
                () -> plan("SHOW qdrant.speed")).getSQLState());
        assertThrows(SQLFeatureNotSupportedException.class, () -> plan("SET qdrant.timeout = ?"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SET qdrant.timeout", "SET qdrant.timeout 30", "SHOW", "RESET"})
    void rejectsIncompleteStatements(String sql) {
        assertThrows(SQLSyntaxErrorException.class, () -> plan(sql));
    }

    private Plan plan(String sql) throws SQLException {
        return planner.plan(Parser.parse(sql), NO_LOOKUPS);
    }
}
