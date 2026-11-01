package com.runaxsoftware.qdrant.jdbc.sql.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.And;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Comparison;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.FunctionCall;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.InList;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.IsNull;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Literal;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Not;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Or;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.OrderItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Parameter;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Star;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.VectorLiteral;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AlterViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Assignment;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ColumnSpec;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateIndexStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DeleteStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropIndexStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.InsertStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.TruncateStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.UpdateStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLSyntaxErrorException;
import java.util.List;
import java.util.Map;

// A parser bug usually shows up as a loop that never ends. A separate thread lets the timeout
// stop such a loop: on the test's own thread it could only ask, and the loop would ignore it
@Timeout(value = 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ParserTest {

    // SELECT

    @Test
    void parsesSelectStar() throws SQLSyntaxErrorException {
        assertEquals(new SelectStatement(star(), TableName.of("docs"), null, null, List.of(), List.of(), null, null),
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
    void parsesTheNameAStatementGivesItsTable() throws SQLSyntaxErrorException {
        assertEquals("t", select("SELECT * FROM docs t").alias());
        assertEquals("t", select("SELECT * FROM docs AS t").alias());
        assertEquals("My Docs", select("SELECT * FROM docs AS \"My Docs\"").alias());
        assertEquals("d", select("SELECT * FROM collections.docs d").alias());
        assertNull(select("SELECT * FROM docs").alias());
        assertNull(select("SELECT * FROM docs WHERE lang = 'en'").alias());
        assertNull(select("SELECT * FROM docs LIMIT 5").alias());
        assertNull(select("SELECT * FROM docs ORDER BY price").alias());
    }

    /**
     * What DataGrip's data editor sends when a collection is opened.
     */
    @Test
    void parsesAQualifiedStar() throws SQLSyntaxErrorException {
        SelectStatement select = select("SELECT t.* FROM collections.sample_products t");

        assertEquals(List.of(new SelectItem(new Star("t"), null)), select.items());
        assertEquals(TableName.of("collections", "sample_products"), select.table());
        assertEquals("t", select.alias());
    }

    @Test
    void parsesAStarAsAnItemOfTheSelectList() throws SQLSyntaxErrorException {
        assertEquals(star(), select("SELECT * FROM docs").items());
        assertEquals(List.of(new SelectItem(new Star("t"), null), column("name")),
                select("SELECT t.*, name FROM docs t").items());
    }

    @Test
    void parsesQualifiedColumns() throws SQLSyntaxErrorException {
        assertEquals(List.of(new SelectItem(new Column("t", "name"), null),
                        new SelectItem(new Column("t", "price"), "cost")),
                select("SELECT t.name, t.price AS cost FROM docs t").items());

        SelectStatement select =
                select("SELECT * FROM docs t WHERE t.lang = 'en' ORDER BY t.price DESC");

        assertEquals(new Comparison(new Column("t", "lang"), "=", new Literal("en")),
                select.where());
        assertEquals(List.of(new OrderItem(new Column("t", "price"), true)), select.orderBy());
    }

    @Test
    void readsADottedNameWithoutATableAsTheNameItIs() throws SQLSyntaxErrorException {
        // Which of the two it is, the planner decides: a quoted path is one name
        assertEquals(List.of(new SelectItem(new Column("specs", "color"), null)),
                select("SELECT specs.color FROM docs").items());
        assertEquals(List.of(column("specs.color")),
                select("SELECT \"specs.color\" FROM docs").items());
    }

    @Test
    void readsKeywordsInAnyCaseAndKeepsTheCaseOfNames() throws SQLSyntaxErrorException {
        assertEquals(new SelectStatement(List.of(column("Name")), TableName.of("MyDocs"), null, eq("lang", "en"), List.of(), List.of(), 5L, null),
                Parser.parse("select Name from MyDocs Where lang = 'en' limit 5"));
    }

    // Vector literals and calls

    @Test
    void parsesASimilaritySearch() throws SQLSyntaxErrorException {
        SelectStatement select = select("SELECT _id, _score FROM docs "
                + "ORDER BY similarity(dense, [0.1, 0.2, 0.3]) DESC LIMIT 10");

        assertEquals(List.of(new OrderItem(new FunctionCall("similarity", List.of(
                        Column.of("dense"), new VectorLiteral(List.of(0.1, 0.2, 0.3)))), true)),
                select.orderBy());
        assertEquals(List.of(column("_id"), column("_score")), select.items());
        assertEquals(10L, select.limit());
    }

    @Test
    void parsesAVectorLiteralOfWholeAndNegativeNumbers() throws SQLSyntaxErrorException {
        assertEquals(new VectorLiteral(List.of(1.0, -0.5, 0.0)),
                similarity("SELECT * FROM docs ORDER BY similarity(dense, [1, -0.5, 0]) DESC")
                        .arguments().get(1));
        assertEquals(new VectorLiteral(List.of(0.25)),
                similarity("SELECT * FROM docs ORDER BY similarity(dense, [0.25]) DESC")
                        .arguments().get(1));
    }

    @Test
    void parsesACallUnderTheTablesName() throws SQLSyntaxErrorException {
        assertEquals(List.of(new Column("t", "dense"), new VectorLiteral(List.of(0.1))),
                similarity("SELECT * FROM docs t ORDER BY similarity(t.dense, [0.1]) DESC")
                        .arguments());
    }

    @Test
    void keepsTheCaseOfAFunctionName() throws SQLSyntaxErrorException {
        assertEquals("SIMILARITY",
                similarity("SELECT * FROM docs ORDER BY SIMILARITY(dense, [0.1]) DESC").name());
    }

    @Test
    void parsesACallWithoutArguments() throws SQLSyntaxErrorException {
        assertEquals(new FunctionCall("random", List.of()),
                similarity("SELECT * FROM docs ORDER BY random() DESC"));
    }

    @Test
    void parsesACallInAWhereCondition() throws SQLSyntaxErrorException {
        assertEquals(new Comparison(new FunctionCall("similarity", List.of(Column.of("dense"),
                        new VectorLiteral(List.of(0.1)))), ">=", new Literal(0.8)),
                where("similarity(dense, [0.1]) >= 0.8"));
    }

    @Test
    void rejectsAVectorLiteralThatIsEmptyOrNotNumbers() {
        assertError("Expected a number, found ']' at line 1, column 48",
                "SELECT * FROM docs ORDER BY similarity(dense, []) DESC");
        assertError("Expected a number, found 'x' at line 1, column 48",
                "SELECT * FROM docs ORDER BY similarity(dense, [x]) DESC");
        // The lexer takes the quotes off a string, so the error shows the text it holds
        assertError("Expected a number, found 'a' at line 1, column 48",
                "SELECT * FROM docs ORDER BY similarity(dense, ['a']) DESC");
    }

    @Test
    void rejectsAnUnclosedVectorLiteral() {
        assertError("Expected ], found the end of the statement at line 1, column 56",
                "SELECT * FROM docs ORDER BY similarity(dense, [0.1, 0.2");
    }

    @Test
    void rejectsAnUnclosedCall() {
        assertError("Expected ), found the end of the statement at line 1, column 52",
                "SELECT * FROM docs ORDER BY similarity(dense, [0.1]");
    }

    // Counting

    @Test
    void parsesACountOfEveryPoint() throws SQLSyntaxErrorException {
        assertEquals(List.of(new SelectItem(count("COUNT"), null)),
                select("SELECT COUNT(*) FROM docs").items());
        assertEquals(List.of(new SelectItem(count("count"), "total")),
                select("select count(*) as total from docs t").items());
    }

    @Test
    void parsesWhatACountCantBe() throws SQLSyntaxErrorException {
        // Each of these parses and the planner is what reports it: a count of a field,
        assertEquals(List.of(new SelectItem(new FunctionCall("COUNT",
                        List.of(Column.of("price"))), null)),
                select("SELECT COUNT(price) FROM docs").items());

        // another aggregate,
        assertEquals(List.of(new SelectItem(new FunctionCall("SUM",
                        List.of(Column.of("price"))), null)),
                select("SELECT SUM(price) FROM docs").items());

        // and a count among other items
        assertEquals(List.of(column("lang"), new SelectItem(count("COUNT"), null)),
                select("SELECT lang, COUNT(*) FROM docs").items());
    }

    @Test
    void rejectsADistinctCount() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> Parser.parse("SELECT COUNT(DISTINCT lang) FROM docs"));

        assertEquals("DISTINCT isn't supported in a call: Qdrant counts points, and a field's "
                + "distinct values come from its facet API at line 1, column 14",
                error.getMessage());
        assertEquals("42000", error.getSQLState());
    }

    @Test
    void rejectsSelectDistinct() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
                () -> Parser.parse("SELECT DISTINCT lang FROM docs"));

        assertEquals("SELECT DISTINCT isn't supported yet: a field's distinct values come from "
                + "Qdrant's facet API at line 1, column 8", error.getMessage());
        assertEquals("42000", error.getSQLState());
    }

    @Test
    void parsesGroupBy() throws SQLSyntaxErrorException {
        assertEquals(List.of(Column.of("lang")),
                select("SELECT lang, COUNT(*) FROM docs GROUP BY lang LIMIT 5").groupBy());
        assertEquals(List.of(new Column("t", "lang")),
                select("SELECT COUNT(*) FROM docs t GROUP BY t.lang LIMIT 5").groupBy());
        assertEquals(List.of(Column.of("lang"), Column.of("year")),
                select("SELECT COUNT(*) FROM docs GROUP BY lang, year LIMIT 5").groupBy());
        // Which of the two a dotted name is, the planner decides, as in a select list
        assertEquals(List.of(new Column("specs", "color")),
                select("SELECT COUNT(*) FROM docs GROUP BY specs.color LIMIT 5").groupBy());
        assertEquals(List.of(), select("SELECT * FROM docs").groupBy());
    }

    @Test
    void rejectsAGroupWithoutBy() {
        assertError("Expected BY, found 'lang' at line 1, column 39",
                "SELECT lang, COUNT(*) FROM docs GROUP lang");
        assertError("Expected a name, found the end of the statement at line 1, column 41",
                "SELECT lang, COUNT(*) FROM docs GROUP BY");
    }

    // SELECT without FROM

    @Test
    void parsesASelectOfConstantsWithoutFrom() throws SQLSyntaxErrorException {
        assertEquals(new SelectStatement(List.of(new SelectItem(new Literal("keep alive"), null)), null, null,
                        null, List.of(), List.of(), null, null),
                Parser.parse("select 'keep alive'"));
        assertEquals(List.of(new SelectItem(new Literal(1L), null), new SelectItem(new Literal(2.5), null),
                        new SelectItem(new Literal(true), null)),
                select("SELECT 1, 2.5, TRUE").items());
    }

    @Test
    void parsesAliases() throws SQLSyntaxErrorException {
        assertEquals(List.of(new SelectItem(new Literal(1L), "one"), new SelectItem(Column.of("name"), "title")),
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
        assertEquals(new Comparison(Column.of("price"), operator, new Literal(10L)),
                where("price " + operator + " 10"));
    }

    @Test
    void numbersParametersInOrderFromOne() throws SQLSyntaxErrorException {
        assertEquals(
                new And(
                        new Comparison(Column.of("a"), "=", new Parameter(1)),
                        new InList(Column.of("b"), List.of(new Parameter(2), new Parameter(3)), false)),
                where("a = ? AND b IN (?, ?)"));
    }

    // WHERE conditions

    @Test
    void parsesInAndNotIn() throws SQLSyntaxErrorException {
        assertEquals(new InList(Column.of("lang"), List.of(new Literal("en"), new Literal("de")), false),
                where("lang IN ('en', 'de')"));
        assertEquals(new InList(Column.of("lang"), List.of(new Literal("en")), true),
                where("lang NOT IN ('en')"));
    }

    @Test
    void parsesIsNullAndIsNotNull() throws SQLSyntaxErrorException {
        assertEquals(new IsNull(Column.of("tags"), false), where("tags IS NULL"));
        assertEquals(new IsNull(Column.of("tags"), true), where("tags IS NOT NULL"));
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
                List.of(new OrderItem(Column.of("price"), true), new OrderItem(Column.of("name"), false),
                        new OrderItem(Column.of("_id"), false)),
                select("SELECT * FROM docs ORDER BY price DESC, name ASC, _id").orderBy());
    }

    @Test
    void parsesLimitAndOffset() throws SQLSyntaxErrorException {
        SelectStatement select = select("SELECT * FROM docs LIMIT 10 OFFSET 20");

        assertEquals(10L, select.limit());
        assertEquals(20L, select.offset());
    }

    @Test
    void parsesAnOffsetWithoutALimit() throws SQLSyntaxErrorException {
        SelectStatement select = select("SELECT * FROM docs OFFSET 20");

        assertNull(select.limit());
        assertEquals(20L, select.offset());
        assertNull(select("SELECT * FROM docs LIMIT 10").offset());
    }

    @Test
    void rejectsAnOffsetThatIsNotAWholeNumberOfRows() {
        for (String offset : List.of("-1", "2.5", "'2'")) {
            assertThrows(SQLSyntaxErrorException.class,
                () -> Parser.parse("SELECT * FROM docs OFFSET " + offset), offset);
        }
    }

    @Test
    void parsesEveryClauseTogether() throws SQLSyntaxErrorException {
        assertEquals(
                new SelectStatement(List.of(column("name")), TableName.of("docs"), null,
                        eq("lang", "en"), List.of(), List.of(new OrderItem(Column.of("price"), true)), 10L,
                        5L),
                Parser.parse("SELECT name FROM docs WHERE lang = 'en' ORDER BY price DESC LIMIT 10 OFFSET 5"));
    }

    // Errors

    @Test
    void rejectsStatementsItDoesntRead() {
        assertError("Expected SELECT, INSERT, UPDATE, DELETE, TRUNCATE, CREATE, ALTER, DROP, SET, "
            + "RESET or SHOW, "
            + "found 'MERGE' at line 1, column 1", "MERGE INTO docs");
    }

    @Test
    void rejectsAnEmptyStatement() {
        assertError("Expected SELECT, INSERT, UPDATE, DELETE, TRUNCATE, CREATE, ALTER, DROP, SET, "
            + "RESET or SHOW, "
            + "found the end of the statement at line 1, column 1", "");
    }

    // DELETE, TRUNCATE and DROP TABLE

    @Test
    void parsesADeleteWithItsCondition() throws SQLSyntaxErrorException {
        assertEquals(new DeleteStatement(TableName.of("docs"), null,
                new Comparison(Column.of("price"), ">", new Literal(5L))),
            Parser.parse("DELETE FROM docs WHERE price > 5"));
        assertEquals(new DeleteStatement(TableName.of("collections", "docs"), "t",
                new Comparison(new Column("t", "_id"), "=", new Literal(7L))),
            Parser.parse("delete from collections.docs as t where t._id = 7"));
        assertEquals(new DeleteStatement(TableName.of("docs"), null, null),
            Parser.parse("DELETE FROM docs"));
    }

    @Test
    void parsesATruncateWithOrWithoutTable() throws SQLSyntaxErrorException {
        assertEquals(new TruncateStatement(TableName.of("docs")), Parser.parse("TRUNCATE TABLE docs"));
        assertEquals(new TruncateStatement(TableName.of("docs")), Parser.parse("truncate docs"));
    }

    @Test
    void parsesADropTableWithOrWithoutIfExists() throws SQLSyntaxErrorException {
        assertEquals(new DropTableStatement(TableName.of("docs"), false), Parser.parse("DROP TABLE docs"));
        assertEquals(new DropTableStatement(TableName.of("docs"), true),
            Parser.parse("drop table if exists docs"));
        assertEquals(new DropTableStatement(TableName.of("if"), false), Parser.parse("DROP TABLE if"),
            "a collection may be called if");
    }

    // Indexes and views

    @Test
    void parsesCreateIndexInEveryForm() throws SQLSyntaxErrorException {
        assertEquals(new CreateIndexStatement(TableName.of("docs"), Column.of("lang"), null, null,
                Map.of(), false), Parser.parse("CREATE INDEX ON docs (lang)"));
        assertEquals(new CreateIndexStatement(TableName.of("collections", "docs"),
                Column.of("title"), "title_idx", "TEXT",
                Map.of("tokenizer", new Literal("word"), "phrase_matching", new Literal(true),
                    "min_token_len", new Literal(2L)), true),
            Parser.parse("create index if not exists title_idx on collections.docs (title) "
                + "using text with (tokenizer = word, phrase_matching = true, MIN_TOKEN_LEN = 2)"));
    }

    @Test
    void parsesDropIndexByNameOrField() throws SQLSyntaxErrorException {
        assertEquals(new DropIndexStatement(TableName.of("docs"), "lang_idx", null, false),
            Parser.parse("DROP INDEX lang_idx ON docs"));
        assertEquals(new DropIndexStatement(TableName.of("docs"), null, Column.of("specs.color"), true),
            Parser.parse("DROP INDEX IF EXISTS ON docs (\"specs.color\")"));
    }

    @Test
    void parsesTheViewStatements() throws SQLSyntaxErrorException {
        CreateViewStatement create = (CreateViewStatement) Parser.parse(
            "CREATE OR REPLACE VIEW current AS SELECT * FROM docs_v2");
        assertEquals(TableName.of("current"), create.view());
        assertEquals(TableName.of("docs_v2"), create.query().table());
        assertEquals(true, create.orReplace());

        assertEquals(new AlterViewStatement(TableName.of("current"), "live"),
            Parser.parse("ALTER VIEW current RENAME TO live"));
        assertEquals(new DropViewStatement(TableName.of("collections", "current"), true),
            Parser.parse("drop view if exists collections.current"));
    }

    @Test
    void rejectsIndexAndViewStatementsItCantRead() {
        assertError("Expected ): a Qdrant payload index is on one field, found ',' at line 1, "
            + "column 27", "CREATE INDEX ON docs (lang, year)");
        assertError("Expected SELECT * FROM the collection the view shows, found 'docs' at line 1, "
            + "column 18", "CREATE VIEW v AS docs");
        assertError("Expected VIEW: ALTER TABLE isn't supported yet, found 'TABLE' at line 1, "
            + "column 7", "ALTER TABLE docs RENAME TO x");
        assertError("Expected TABLE, INDEX, VIEW or OR REPLACE VIEW, found 'SEQUENCE' at line 1, "
            + "column 8", "CREATE SEQUENCE s");
    }

    // INSERT

    @Test
    void parsesAnInsertOfSeveralRows() throws SQLSyntaxErrorException {
        assertEquals(new InsertStatement(TableName.of("collections", "docs"),
                List.of(Column.of("_id"), Column.of("dense"), Column.of("specs.color"), Column.of("lang")),
                List.of(
                    List.of(new Literal(1L), new VectorLiteral(List.of(0.1, 0.2)),
                        new Literal("red"), new Literal(null)),
                    List.of(new Parameter(1), new FunctionCall("embed", List.of(new Literal("refund"))),
                        new Parameter(2), new Literal("en")))),
            Parser.parse("INSERT INTO collections.docs (_id, dense, \"specs.color\", lang) VALUES "
                + "(1, [0.1, 0.2], 'red', NULL), (?, embed('refund'), ?, 'en')"));
    }

    @Test
    void rejectsAnInsertWithoutItsColumns() {
        assertError("Expected the columns in brackets, as INSERT INTO docs (_id, lang) VALUES (...), "
            + "found 'VALUES' at line 1, column 18", "INSERT INTO docs VALUES (1)");
        assertError("Expected VALUES, found the end of the statement at line 1, column 24",
            "INSERT INTO docs (lang)");
    }

    // UPDATE

    @Test
    void parsesAnUpdateAsADataEditorWritesIt() throws SQLSyntaxErrorException {
        assertEquals(new UpdateStatement(TableName.of("collections", "docs"), "t", List.of(
                new Assignment(Column.of("lang"), new Parameter(1)),
                new Assignment(new Column("t", "year"), new Literal(2024L)),
                new Assignment(Column.of("specs.color"), new Literal(null))),
                new Comparison(Column.of("_id"), "=", new Parameter(2))),
            Parser.parse("UPDATE collections.docs AS t SET lang = ?, t.year = 2024, "
                + "\"specs.color\" = NULL WHERE _id = ?"));
        assertEquals(new UpdateStatement(TableName.of("docs"), null, List.of(
                new Assignment(Column.of("lang"), new Literal("de"))), null),
            Parser.parse("update docs set lang = 'de'"));
    }

    @Test
    void rejectsAnUpdateWithoutSet() {
        assertError("Expected SET, found 'lang' at line 1, column 15", "UPDATE docs t lang = 1");
        assertError("Expected =, found 'de' at line 1, column 22", "UPDATE docs SET lang 'de'");
    }

    // CREATE TABLE

    @Test
    void parsesACreateTableWithVectorsAndIndexes() throws SQLSyntaxErrorException {
        assertEquals(new CreateTableStatement(TableName.of("docs"), false, List.of(
                new ColumnSpec("dense", "VECTOR", 384, "COSINE", null, false),
                new ColumnSpec("colbert", "MULTIVECTOR", 128, "DOT", null, false),
                new ColumnSpec("keywords", "SPARSE_VECTOR", null, null, "IDF", false),
                new ColumnSpec("lang", "KEYWORD", null, null, null, true),
                new ColumnSpec("specs.color", "KEYWORD", null, null, null, true))),
            Parser.parse("CREATE TABLE docs (dense VECTOR(384) DISTANCE COSINE, "
                + "colbert multivector(128) distance dot, keywords SPARSE_VECTOR MODIFIER IDF, "
                + "lang KEYWORD INDEX, \"specs.color\" keyword index)"));
    }

    @Test
    void parsesCreateTableIfNotExists() throws SQLSyntaxErrorException {
        assertEquals(new CreateTableStatement(TableName.of("collections", "docs"), true, List.of(
                new ColumnSpec("_vector", "VECTOR", 4, null, null, false))),
            Parser.parse("create table if not exists collections.docs (_vector vector(4))"));
    }

    @Test
    void rejectsACreateTableWithoutColumns() {
        assertError("Expected a name, found ')' at line 1, column 20", "CREATE TABLE docs ()");
        assertError("Expected a column type, such as VECTOR(384) or KEYWORD INDEX, found ',' at "
            + "line 1, column 25", "CREATE TABLE docs (dense, lang KEYWORD INDEX)");
    }

    @Test
    void rejectsADeleteWithoutFrom() {
        assertError("Expected FROM, found 'docs' at line 1, column 8", "DELETE docs");
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
    void acceptsOneSemicolonAtTheEnd() throws SQLSyntaxErrorException {
        assertEquals(Parser.parse("SELECT * FROM docs LIMIT 5"),
                Parser.parse("SELECT * FROM docs LIMIT 5;"));
        assertEquals(Parser.parse("DELETE FROM docs WHERE _id = ?"),
                Parser.parse("DELETE FROM docs WHERE _id = ?  ;\n"));
        assertEquals(Parser.parse("SELECT ';' AS semicolon"), Parser.parse("SELECT ';' AS semicolon;"),
                "a semicolon in quotes is text");
    }

    @Test
    void rejectsSemicolonsThatDontEndTheStatement() {
        assertError("Expected the end of the statement: run one statement at a time, found 'SELECT' "
                + "at line 1, column 11", "SELECT 1; SELECT 2");
        assertError("Expected the end of the statement: run one statement at a time, found ';' at "
                + "line 1, column 10", "SELECT 1;;");
        assertError("Expected SELECT, INSERT, UPDATE, DELETE, TRUNCATE, CREATE, ALTER, DROP, SET, "
            + "RESET or SHOW, "
                + "found ';' at line 1, column 1", ";");
    }

    @Test
    void rejectsLeftoverText() {
        assertError("Expected the end of the statement, found 'extra' at line 1, column 22",
                "SELECT * FROM docs t extra");
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

    /** The call a statement's single ORDER BY item is. */
    private static FunctionCall similarity(String sql) throws SQLSyntaxErrorException {
        return (FunctionCall) select(sql).orderBy().get(0).expression();
    }

    private static List<SelectItem> star() {
        return List.of(new SelectItem(Star.of(), null));
    }

    private static SelectItem column(String name) {
        return new SelectItem(Column.of(name), null);
    }

    /** A count of every point, as the statement wrote the function's name. */
    private static FunctionCall count(String name) {
        return new FunctionCall(name, List.of(Star.of()));
    }

    private static Comparison eq(String column, Object value) {
        return new Comparison(Column.of(column), "=", new Literal(value));
    }

    private static void assertError(String message, String sql) {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class, () -> Parser.parse(sql));

        assertEquals(message, error.getMessage());
    }
}
