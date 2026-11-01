package com.runaxsoftware.qdrant.jdbc.sql.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.VectorLiteral;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLSyntaxErrorException;
import java.util.List;

class ParametersTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SELECT * FROM docs                                               | 0",
            "SELECT ?, ? AS b                                                 | 2",
            "SELECT * FROM docs WHERE lang = ? AND (year > ? OR NOT x IN (?, ?)) | 4",
            "SELECT _id FROM docs ORDER BY similarity(dense, ?) DESC LIMIT 5  | 1",
            "DELETE FROM docs WHERE _id = ?                                   | 1",
            "DROP TABLE docs                                                  | 0"})
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
