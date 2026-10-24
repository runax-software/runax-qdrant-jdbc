package com.runaxsoftware.qdrant.jdbc.sql.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.And;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.Comparison;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.InList;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.IsNull;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.Not;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.Or;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.OrderItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.SelectStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLSyntaxErrorException;
import java.util.List;

// A parser bug usually shows up as a loop that never ends. A separate thread lets the timeout
// stop such a loop: on the test's own thread it could only ask, and the loop would ignore it
@Timeout(value = 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ParserTest {

    // SELECT

    @Test
    void parsesSelectStar() throws SQLSyntaxErrorException {
        assertEquals(new SelectStatement(List.of(), "docs", null, List.of(), null, null),
                Parser.parse("SELECT * FROM docs"));
    }

    @Test
    void parsesSelectedColumns() throws SQLSyntaxErrorException {
        assertEquals(List.of("name", "price", "_id"),
                select("SELECT name, price, _id FROM docs").columns());
    }

    @Test
    void parsesQuotedNames() throws SQLSyntaxErrorException {
        SelectStatement select = select("SELECT \"specs.color\" FROM \"My Docs\"");

        assertEquals(List.of("specs.color"), select.columns());
        assertEquals("My Docs", select.table());
    }

    @Test
    void readsKeywordsInAnyCaseAndKeepsTheCaseOfNames() throws SQLSyntaxErrorException {
        assertEquals(new SelectStatement(List.of("Name"), "MyDocs", eq("lang", "en"), List.of(), 5L, null),
                Parser.parse("select Name from MyDocs Where lang = 'en' limit 5"));
    }

    // WHERE values

    @Test
    void readsStringNumberAndBooleanValues() throws SQLSyntaxErrorException {
        assertEquals(eq("lang", "en"), where("lang = 'en'"));
        assertEquals(eq("year", 2024L), where("year = 2024"));
        assertEquals(eq("price", 9.5), where("price = 9.5"));
        assertEquals(eq("delta", -3L), where("delta = -3"));
        assertEquals(eq("active", true), where("active = true"));
        assertEquals(eq("active", false), where("active = FALSE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "<>", "!=", "<", "<=", ">", ">="})
    void readsEveryComparisonOperator(String operator) throws SQLSyntaxErrorException {
        assertEquals(new Comparison(new Column("price"), operator, new Literal(10L)),
                where("price " + operator + " 10"));
    }

    @Test
    void numbersParametersInOrderFromOne() throws SQLSyntaxErrorException {
        assertEquals(
                new And(
                        new Comparison(new Column("a"), "=", new Parameter(1)),
                        new InList(new Column("b"), List.of(new Parameter(2), new Parameter(3)), false)),
                where("a = ? AND b IN (?, ?)"));
    }

    // WHERE conditions

    @Test
    void parsesInAndNotIn() throws SQLSyntaxErrorException {
        assertEquals(new InList(new Column("lang"), List.of(new Literal("en"), new Literal("de")), false),
                where("lang IN ('en', 'de')"));
        assertEquals(new InList(new Column("lang"), List.of(new Literal("en")), true),
                where("lang NOT IN ('en')"));
    }

    @Test
    void parsesIsNullAndIsNotNull() throws SQLSyntaxErrorException {
        assertEquals(new IsNull(new Column("tags"), false), where("tags IS NULL"));
        assertEquals(new IsNull(new Column("tags"), true), where("tags IS NOT NULL"));
    }

    @Test
    void bindsAndTighterThanOr() throws SQLSyntaxErrorException {
        assertEquals(new Or(eq("a", 1L), new And(eq("b", 2L), eq("c", 3L))),
                where("a = 1 OR b = 2 AND c = 3"));
    }

    @Test
    void letsParenthesesOverridePrecedence() throws SQLSyntaxErrorException {
        assertEquals(new And(new Or(eq("a", 1L), eq("b", 2L)), eq("c", 3L)),
                where("(a = 1 OR b = 2) AND c = 3"));
    }

    @Test
    void chainsAndOrFromTheLeft() throws SQLSyntaxErrorException {
        assertEquals(new And(new And(eq("a", 1L), eq("b", 2L)), eq("c", 3L)),
                where("a = 1 AND b = 2 AND c = 3"));
    }

    @Test
    void bindsNotTighterThanAnd() throws SQLSyntaxErrorException {
        assertEquals(new And(new Not(eq("a", 1L)), eq("b", 2L)), where("NOT a = 1 AND b = 2"));
        assertEquals(new Not(new Not(eq("a", 1L))), where("NOT NOT a = 1"));
    }

    // ORDER BY, LIMIT, OFFSET

    @Test
    void parsesOrderByWithDirections() throws SQLSyntaxErrorException {
        assertEquals(
                List.of(new OrderItem("price", true), new OrderItem("name", false),
                        new OrderItem("_id", false)),
                select("SELECT * FROM docs ORDER BY price DESC, name ASC, _id").orderBy());
    }

    @Test
    void parsesLimitAndOffset() throws SQLSyntaxErrorException {
        SelectStatement select = select("SELECT * FROM docs LIMIT 10 OFFSET 20");

        assertEquals(10L, select.limit());
        assertEquals(20L, select.offset());
    }

    @Test
    void parsesEveryClauseTogether() throws SQLSyntaxErrorException {
        assertEquals(
                new SelectStatement(List.of("name"), "docs", eq("lang", "en"),
                        List.of(new OrderItem("price", true)), 10L, 5L),
                Parser.parse("SELECT name FROM docs WHERE lang = 'en' ORDER BY price DESC LIMIT 10 OFFSET 5"));
    }

    // Errors

    @Test
    void rejectsStatementsOtherThanSelect() {
        assertError("Expected SELECT, found 'DELETE' at position 0", "DELETE FROM docs");
    }

    @Test
    void rejectsAnEmptyStatement() {
        assertError("Expected SELECT, found the end of the statement at position 0", "");
    }

    @Test
    void rejectsAMissingFrom() {
        assertError("Expected FROM, found 'docs' at position 12", "SELECT name docs");
    }

    @Test
    void rejectsAMissingValueAtTheEnd() {
        assertError("Expected a value, found the end of the statement at position 32",
                "SELECT * FROM docs WHERE price >");
    }

    @Test
    void rejectsAColumnInPlaceOfAValue() {
        assertError("Expected a value, found 'b' at position 29", "SELECT * FROM docs WHERE a = b");
    }

    @Test
    void rejectsNotWithoutIn() {
        assertError("Expected IN after NOT, found '=' at position 31", "SELECT * FROM docs WHERE a NOT = 1");
    }

    @Test
    void rejectsAnUnclosedParenthesis() {
        assertError("Expected ), found the end of the statement at position 31",
                "SELECT * FROM docs WHERE (a = 1");
    }

    @Test
    void rejectsAnUnclosedInList() {
        assertError("Expected ), found the end of the statement at position 32",
                "SELECT * FROM docs WHERE a IN (1");
    }

    @Test
    void rejectsLeftoverText() {
        assertError("Expected the end of the statement, found 'extra' at position 19",
                "SELECT * FROM docs extra");
    }

    @Test
    void rejectsALimitThatIsNotAWholeNumber() {
        assertError("Expected a whole number, found '1.5' at position 25", "SELECT * FROM docs LIMIT 1.5");
        assertError("Expected a whole number, found '-1' at position 25", "SELECT * FROM docs LIMIT -1");
    }

    // Helpers

    private static SelectStatement select(String sql) throws SQLSyntaxErrorException {
        return (SelectStatement) Parser.parse(sql);
    }

    private static Expression where(String condition) throws SQLSyntaxErrorException {
        return select("SELECT * FROM docs WHERE " + condition).where();
    }

    private static Comparison eq(String column, Object value) {
        return new Comparison(new Column(column), "=", new Literal(value));
    }

    private static void assertError(String message, String sql) {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class, () -> Parser.parse(sql));

        assertEquals(message, error.getMessage());
    }
}
