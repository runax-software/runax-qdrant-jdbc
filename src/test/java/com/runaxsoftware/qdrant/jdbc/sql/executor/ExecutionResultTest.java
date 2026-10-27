package com.runaxsoftware.qdrant.jdbc.sql.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.resultset.ListResultSet;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;

class ExecutionResultTest {

    private static final ResultSet ROWS = ListResultSet.empty(List.of(ResultColumn.of("x", Types.VARCHAR)));

    @Test
    void holdsTheRowsOfASelect() {
        ExecutionResult result = ExecutionResult.rows(ROWS);

        assertSame(ROWS, result.resultSet());
        assertEquals(-1, result.updateCount());
        assertTrue(result.hasResultSet());
    }

    @Test
    void holdsTheCountOfAWrite() {
        ExecutionResult result = ExecutionResult.updated(3);

        assertNull(result.resultSet());
        assertEquals(3, result.updateCount());
        assertFalse(result.hasResultSet());
    }

    @Test
    void allowsWritesThatChangeNothing() {
        assertEquals(0, ExecutionResult.updated(0).updateCount());
        assertFalse(ExecutionResult.updated(0).hasResultSet());
    }

    @Test
    void allowsCountsBeyondAnInt() {
        assertEquals(5_000_000_000L, ExecutionResult.updated(5_000_000_000L).updateCount());
    }

    @Test
    void needsEitherRowsOrACount() {
        IllegalArgumentException neither = assertThrows(IllegalArgumentException.class,
            () -> new ExecutionResult(null, -1));
        IllegalArgumentException both = assertThrows(IllegalArgumentException.class,
            () -> new ExecutionResult(ROWS, 2));

        assertTrue(neither.getMessage().contains("not both or neither"), neither.getMessage());
        assertTrue(both.getMessage().contains("not both or neither"), both.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ExecutionResult.rows(null));
    }

    @Test
    void rejectsNegativeCounts() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ExecutionResult.updated(-2));

        assertTrue(error.getMessage().contains("negative"), error.getMessage());
    }

    @Test
    void comparesByValue() {
        assertEquals(ExecutionResult.updated(7), new ExecutionResult(null, 7));
        assertEquals(ExecutionResult.rows(ROWS), new ExecutionResult(ROWS, -1));
    }
}
