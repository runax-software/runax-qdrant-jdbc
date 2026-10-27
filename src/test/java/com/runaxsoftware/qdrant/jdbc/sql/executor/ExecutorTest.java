package com.runaxsoftware.qdrant.jdbc.sql.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.Planner;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ConstantPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

class ExecutorTest {

    // A statement the executor may hand to its results but must never call
    private static final Statement STATEMENT = (Statement) Proxy.newProxyInstance(
        ExecutorTest.class.getClassLoader(), new Class<?>[] {Statement.class},
        (proxy, method, arguments) -> {
            throw new AssertionError("Called Statement." + method.getName());
        });

    private final Executor executor = new Executor();

    @Test
    void runsAConstantPlanAsRowsOwnedByTheStatement() throws SQLException {
        ConstantPlan plan = new ConstantPlan(
            List.of(ResultColumn.of("one", Types.BIGINT), ResultColumn.of("?column?", Types.VARCHAR)),
            List.of(Arrays.asList(1L, "x")));

        ExecutionResult result = executor.execute(plan, STATEMENT);

        assertTrue(result.hasResultSet());
        assertEquals(-1, result.updateCount());

        try (ResultSet rows = result.resultSet()) {
            assertSame(STATEMENT, rows.getStatement());
            assertEquals(2, rows.getMetaData().getColumnCount());
            assertEquals("one", rows.getMetaData().getColumnLabel(1));
            assertEquals(Types.BIGINT, rows.getMetaData().getColumnType(1));
            assertEquals("?column?", rows.getMetaData().getColumnLabel(2));

            assertTrue(rows.next());
            assertEquals(1L, rows.getLong("one"));
            assertEquals("x", rows.getString(2));
            assertFalse(rows.next());
        }
    }

    @Test
    void keepsNullValues() throws SQLException {
        ConstantPlan plan = new ConstantPlan(List.of(ResultColumn.of("nothing", Types.VARCHAR)),
            List.of(Arrays.asList((Object) null)));

        try (ResultSet rows = executor.execute(plan, STATEMENT).resultSet()) {
            assertTrue(rows.next());
            assertEquals(null, rows.getString(1));
            assertTrue(rows.wasNull());
        }
    }

    @Test
    void runsAConstantPlanWithoutRows() throws SQLException {
        ConstantPlan plan = new ConstantPlan(List.of(ResultColumn.of("one", Types.BIGINT)), List.of());

        try (ResultSet rows = executor.execute(plan, STATEMENT).resultSet()) {
            assertEquals("one", rows.getMetaData().getColumnLabel(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void returnsAFreshResultForEachRun() throws SQLException {
        ConstantPlan plan = new ConstantPlan(List.of(ResultColumn.of("one", Types.BIGINT)),
            List.of(List.of(1L)));

        ResultSet first = executor.execute(plan, STATEMENT).resultSet();
        ResultSet second = executor.execute(plan, STATEMENT).resultSet();

        assertNotSame(first, second);
        first.close();
        assertTrue(second.next(), "closing one result leaves the other readable");
        second.close();
    }

    @Test
    void runsWhatThePlannerProduces() throws SQLException {
        Planner planner = new Planner();
        ExecutionResult result = executor.execute(
            planner.plan(Parser.parse("SELECT 'keep alive' AS ping, 2.5"), name -> {
                throw new AssertionError("Looked up " + name);
            }), STATEMENT);

        try (ResultSet rows = result.resultSet()) {
            assertTrue(rows.next());
            assertEquals("keep alive", rows.getString("ping"));
            assertEquals(2.5, rows.getDouble(2));
        }
    }

    @Test
    void refusesPlansItCantRunYet() {
        ScrollPlan plan = new ScrollPlan("docs", List.of(new ColumnDefinition("_id", ColumnKind.ID,
            ColumnType.TEXT, 0, null, "")), false, false, null);

        SQLFeatureNotSupportedException error = assertThrows(SQLFeatureNotSupportedException.class,
            () -> executor.execute(plan, STATEMENT));

        assertTrue(error.getMessage().contains("ScrollPlan"), error.getMessage());
        assertTrue(error.getMessage().contains("isn't supported yet"), error.getMessage());
    }
}
