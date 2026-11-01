package com.runaxsoftware.qdrant.jdbc.sql.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.And;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Comparison;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.InList;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.IsNull;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Not;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Or;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.OrderItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
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
        assertEquals(new SelectStatement(List.of(), TableName.of("docs"), null, List.of(), null, null),
                Parser.parse("SELECT * FROM docs"));
    }

    @Test
    void parsesSelectedColumns() throws SQLSyntaxErrorException {
        assertEquals(List.of(column("name"), column("price"), column("_id")),
                select("SELECT name, price, _id FROM docs").items());
    }

    @Test
    void parsesQuotedNames() throws SQLSyntaxErrorException {
        SelectStatement select = select("SELECT \"specs.color\" FROM \"My Docs\"");

        assertEquals(List.of(column("specs.color")), select.items());
        assertEquals(TableName.of("My Docs"), select.table());
    }

    @Test
    void parsesAQualifiedTableName() throws SQLSyntaxErrorException {
        assertEquals(TableName.of("system", "collections"),
                select("SELECT * FROM system.collections").table());
        assertEquals(TableName.of("collections", "docs"),
                select("SELECT * FROM collections.docs").table());
        assertEquals(new TableName("qdrant", "system", "collections"),
                select("SELECT * FROM qdrant.system.collections").table());
    }

    @Test
    void parsesTheQuotedPartsOfAQualifiedTableName() throws SQLSyntaxErrorException {
        assertEquals(TableName.of("system", "collections"),
                select("SELECT * FROM \"system\".\"collections\"").table());
        assertEquals(TableName.of("collections", "My Docs"),
                select("SELECT * FROM collections.\"My Docs\"").table());
    }

    @Test
    void readsKeywordsInAnyCaseAndKeepsTheCaseOfNames() throws SQLSyntaxErrorException {
        assertEquals(new SelectStatement(List.of(column("Name")), TableName.of("MyDocs"), eq("lang", "en"), List.of(), 5L, null),
                Parser.parse("select Name from MyDocs Where lang = 'en' limit 5"));
    }

    // SELECT without FROM

    @Test
    void parsesASelectOfConstantsWithoutFrom() throws SQLSyntaxErrorException {
        assertEquals(new SelectStatement(List.of(new SelectItem(new Literal("keep alive"), null)), null, null,
                        List.of(), null, null),
                Parser.parse("select 'keep alive'"));
        assertEquals(List.of(new SelectItem(new Literal(1L), null), new SelectItem(new Literal(2.5), null),
                        new SelectItem(new Literal(true), null)),
                select("SELECT 1, 2.5, TRUE").items());
    }

    @Test
    void parsesAliases() throws SQLSyntaxErrorException {
        assertEquals(List.of(new SelectItem(new Literal(1L), "one"), new SelectItem(new Column("name"), "title")),
                select("SELECT 1 AS one, name AS title FROM docs").items());
    }

    @Test
    void parsesLimitWithoutFrom() throws SQLSyntaxErrorException {
        assertEquals(0L, select("SELECT 1 LIMIT 0").limit());
    }

    @Test
    void rejectsAnAliasWithoutAName() {
        assertError("Expected a name, found the end of the statement at line 1, column 12", "SELECT 1 AS");
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
                new SelectStatement(List.of(column("name")), TableName.of("docs"), eq("lang", "en"),
                        List.of(new OrderItem("price", true)), 10L, 5L),
                Parser.parse("SELECT name FROM docs WHERE lang = 'en' ORDER BY price DESC LIMIT 10 OFFSET 5"));
    }

    // Errors

    @Test
    void rejectsStatementsOtherThanSelect() {
        assertError("Expected SELECT, found 'DELETE' at line 1, column 1", "DELETE FROM docs");
    }

    @Test
    void rejectsAnEmptyStatement() {
        assertError("Expected SELECT, found the end of the statement at line 1, column 1", "");
    }

    @Test
    void rejectsAMissingFrom() {
        assertError("Expected FROM, found 'docs' at line 1, column 13", "SELECT name docs");
    }

    @Test
    void rejectsATableNameWithTooManyParts() {
        assertError("A table name has at most catalog, schema, and name, found 'collections' at "
                        + "line 1, column 33",
                "SELECT * FROM qdrant.system.too.collections");
    }

    @Test
    void rejectsATableNamePartThatIsMissing() {
        assertError("Expected a name, found the end of the statement at line 1, column 22",
                "SELECT * FROM system.");
        assertError("Expected a name, found '.' at line 1, column 15", "SELECT * FROM .docs");
    }

    @Test
    void rejectsAMissingValueAtTheEnd() {
        assertError("Expected a value, found the end of the statement at line 1, column 33",
                "SELECT * FROM docs WHERE price >");
    }

    @Test
    void rejectsAColumnInPlaceOfAValue() {
        assertError("Expected a value, found 'b' at line 1, column 30", "SELECT * FROM docs WHERE a = b");
    }

    @Test
    void rejectsNotWithoutIn() {
        assertError("Expected IN after NOT, found '=' at line 1, column 32", "SELECT * FROM docs WHERE a NOT = 1");
    }

    @Test
    void rejectsAnUnclosedParenthesis() {
        assertError("Expected ), found the end of the statement at line 1, column 32",
                "SELECT * FROM docs WHERE (a = 1");
    }

    @Test
    void rejectsAnUnclosedInList() {
        assertError("Expected ), found the end of the statement at line 1, column 33",
                "SELECT * FROM docs WHERE a IN (1");
    }

    @Test
    void givesTheLineAndColumnOfAnErrorOnALaterLine() {
        assertError("Expected a value, found the end of the statement at line 3, column 14",
                "SELECT name\nFROM docs\nWHERE price >");
        assertError("Expected FROM, found 'docs' at line 2, column 1", "SELECT name\r\ndocs");
    }

    @Test
    void rejectsLeftoverText() {
        assertError("Expected the end of the statement, found 'extra' at line 1, column 20",
                "SELECT * FROM docs extra");
    }

    @Test
    void rejectsALimitThatIsNotAWholeNumber() {
        assertError("Expected a whole number, found '1.5' at line 1, column 26", "SELECT * FROM docs LIMIT 1.5");
        assertError("Expected a whole number, found '-1' at line 1, column 26", "SELECT * FROM docs LIMIT -1");
    }

    // Helpers

    private static SelectStatement select(String sql) throws SQLSyntaxErrorException {
        return (SelectStatement) Parser.parse(sql);
    }

    private static Expression where(String condition) throws SQLSyntaxErrorException {
        return select("SELECT * FROM docs WHERE " + condition).where();
    }

    private static SelectItem column(String name) {
        return new SelectItem(new Column(name), null);
    }

    private static Comparison eq(String column, Object value) {
        return new Comparison(new Column(column), "=", new Literal(value));
    }

    private static void assertError(String message, String sql) {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class, () -> Parser.parse(sql));

        assertEquals(message, error.getMessage());
    }
}
