package com.runaxsoftware.qdrant.jdbc.sql.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Comparison;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.VectorLiteral;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.InsertStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.OnConflict;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.Set;

class ParametersTest {

    /**
     * Binding rebuilds the statement, so anything it forgets to copy is silently lost. The ON
     * CONFLICT clause is easy to drop that way, and dropping it turns an upsert back into an
     * insert that fails on a taken ID.
     */
    @Test
    void keepsTheOnConflictClauseWhenItBindsValues() throws SQLSyntaxErrorException {
        Statement parsed = Parser.parse("INSERT INTO docs (_id, lang) VALUES (?, ?) "
                + "ON CONFLICT (_id) DO UPDATE WHERE year < ?");
        InsertStatement bound = (InsertStatement) Parameters.bind(parsed,
                index -> new Literal((long) index));

        assertEquals(OnConflict.Action.UPDATE, bound.onConflict().action());
        assertEquals(new Comparison(Column.of("year"), "<", new Literal(3L)),
                bound.onConflict().where(), "the clause's own values are bound too");
        assertEquals(3, Parameters.count(parsed), "and they're counted");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SELECT * FROM docs                                               | 0",
            "SELECT ?, ? AS b                                                 | 2",
            "SELECT * FROM docs WHERE lang = ? AND (year > ? OR NOT x IN (?, ?)) | 4",
            "SELECT _id FROM docs ORDER BY similarity(dense, ?) DESC LIMIT 5  | 1",
            "DELETE FROM docs WHERE _id = ?                                   | 1",
            "DROP TABLE docs                                                  | 0",
            "SELECT _id FROM docs WHERE lang = ? LIMIT ? OFFSET ?             | 3",
            "SELECT _id FROM docs LIMIT ? BY doc_id LIMIT ?                    | 2",
            "SELECT * FROM distance_matrix(docs, dense) LIMIT ? OFFSET ?      | 2"})
    void countsThePlaceholders(String sql, int count) throws SQLSyntaxErrorException {
        assertEquals(count, Parameters.count(Parser.parse(sql)));
    }

    @Test
    void putsEachValueInItsPlaceholdersPlace() throws SQLSyntaxErrorException {
        Statement bound = Parameters.bind(
                Parser.parse("SELECT ? AS a FROM docs WHERE lang = ? AND year IN (?, 2024) "
                        + "ORDER BY similarity(dense, ?) DESC LIMIT 5"),
                index -> index == 4 ? new VectorLiteral(List.of(0.1, 0.2)) : new Literal((long) index));

        assertEquals(Parser.parse("SELECT 1 AS a FROM docs WHERE lang = 2 AND year IN (3, 2024) "
                + "ORDER BY similarity(dense, [0.1, 0.2]) DESC LIMIT 5"), bound);
    }

    @Test
    void bindsTheLimitOffsetAndRowsPerGroup() throws SQLSyntaxErrorException {
        assertEquals(Parser.parse("SELECT _id FROM docs WHERE lang = 1 ORDER BY similarity(dense, "
                        + "[0.1]) DESC LIMIT 2 BY doc_id LIMIT 3 OFFSET 4"),
                Parameters.bind(Parser.parse("SELECT _id FROM docs WHERE lang = ? ORDER BY "
                                + "similarity(dense, [0.1]) DESC LIMIT ? BY doc_id LIMIT ? OFFSET ?"),
                        index -> new Literal((long) index)));
        assertEquals(Parser.parse("SELECT * FROM distance_matrix(docs, dense) LIMIT 1 OFFSET 2"),
                Parameters.bind(Parser.parse("SELECT * FROM distance_matrix(docs, dense) LIMIT ? OFFSET ?"),
                        index -> new Literal((long) index)));
    }

    /**
     * A LIMIT or OFFSET placeholder takes a whole number, where any other takes whatever its
     * field holds, so parameter metadata can say so for those alone.
     */
    @Test
    void knowsWhichPlaceholdersAreRowCounts() throws SQLSyntaxErrorException {
        assertEquals(Set.of(2, 3, 4), Parameters.rowCounts(Parser.parse(
                "SELECT _id FROM docs WHERE lang = ? LIMIT ? BY doc_id LIMIT ? OFFSET ?")));
        assertEquals(Set.of(1), Parameters.rowCounts(Parser.parse(
                "SELECT * FROM distance_matrix(docs, dense) LIMIT 5 OFFSET ?")));
        assertEquals(Set.of(), Parameters.rowCounts(Parser.parse(
                "SELECT _id FROM docs WHERE lang = ? LIMIT 5 OFFSET 2")));
        assertEquals(Set.of(), Parameters.rowCounts(Parser.parse("DELETE FROM docs WHERE _id = ?")));
    }

    @Test
    void keepsTheGroupByOfAGroupedCount() throws SQLSyntaxErrorException {
        assertEquals(Parser.parse("SELECT lang, COUNT(*) FROM docs WHERE year > 2020 GROUP BY lang LIMIT 5"),
                Parameters.bind(Parser.parse("SELECT lang, COUNT(*) FROM docs WHERE year > ? GROUP BY lang LIMIT 5"),
                        index -> new Literal(2020L)));
    }

    @Test
    void bindsTheConditionOfADelete() throws SQLSyntaxErrorException {
        assertEquals(Parser.parse("DELETE FROM docs WHERE _id = 'a' OR lang IS NULL"),
                Parameters.bind(Parser.parse("DELETE FROM docs WHERE _id = ? OR lang IS NULL"),
                        index -> new Literal("a")));
    }

    @Test
    void leavesAStatementWithoutPlaceholdersAsItIs() throws SQLSyntaxErrorException {
        Statement statement = Parser.parse("SELECT name FROM docs WHERE price > 5");

        assertEquals(statement, Parameters.bind(statement, index -> {
            throw new AssertionError("No placeholder " + index);
        }));
    }
}
