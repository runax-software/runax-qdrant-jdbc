package com.runaxsoftware.qdrant.jdbc.statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.point;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.unnamedVector;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.QdrantDriver;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class QdrantStatementTest {

    private static final String SQL = "SELECT * FROM products";

    private FakeQdrantServer server;
    private Connection connection;
    private Statement statement;

    @BeforeEach
    void open() throws Exception {
        server = FakeQdrantServer.start();
        server.collection("products", unnamedVector(4, "Cosine", Map.of()),
            List.of(point(1, Json.object("name", "Lamp", "price", 39.9))));
        connection = new QdrantDriver().connect(server.jdbcUrl(), new Properties());
        statement = connection.createStatement();
    }

    @AfterEach
    void close() throws Exception {
        statement.close();
        connection.close();
        server.close();
    }

    @Test
    void readsTheRowsOfACollection() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT _id, name AS title, price FROM products")) {
            assertEquals(List.of("_id", "title", "price"), List.of(rows.getMetaData().getColumnLabel(1),
                rows.getMetaData().getColumnLabel(2), rows.getMetaData().getColumnLabel(3)));
            assertSame(statement, rows.getStatement());

            assertTrue(rows.next());
            assertEquals("1", rows.getString("_id"));
            assertEquals("Lamp", rows.getString("title"));
            assertEquals(39.9, rows.getDouble("price"));
            assertFalse(rows.next());
        }
    }

    @Test
    void readsTheRowsOfAnOrderedScrollInOnePage() throws SQLException {
        server.collection("ordered", FakeQdrantServer.unnamedVector(2, "Cosine",
                Map.of("price", "float")),
            List.of(point(1, Json.object("price", 9.5)), point(2, Json.object("price", 1.5))));

        try (ResultSet rows = statement.executeQuery(
            "SELECT _id, price FROM ordered ORDER BY price DESC LIMIT 7")) {
            assertTrue(rows.next());
        }

        JsonNode request = lastScroll();
        assertEquals(7, request.path("limit").asInt(), "the whole read, since there's no cursor");
        assertEquals(Json.object("key", "price", "direction", "desc"), request.get("order_by"));
    }

    @Test
    void reportsAnOrderedReadThatCantRunBeforeRunning() throws SQLException {
        // products has no range index, and the second says nothing about how many to read
        for (String sql : List.of("SELECT _id FROM products ORDER BY name LIMIT 1",
            "SELECT _id FROM products ORDER BY price DESC")) {
            assertThrows(SQLException.class, () -> statement.executeQuery(sql), sql);
        }

        assertTrue(server.scrollRequests().stream().noneMatch(request -> request.has("order_by")),
            server.scrollRequests().toString());
    }

    @Test
    void ranksTheRowsOfASimilaritySearch() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT _id, _score, name FROM products "
            + "ORDER BY similarity(_vector, [0.1, 0.2, 0.3, 0.4]) DESC LIMIT 5")) {
            assertEquals(Types.DOUBLE, rows.getMetaData().getColumnType(2));
            assertEquals("_score", rows.getMetaData().getColumnLabel(2));

            assertTrue(rows.next());
            assertEquals("1", rows.getString("_id"));
            assertEquals(1.0, rows.getDouble("_score"));
            assertEquals("Lamp", rows.getString("name"));
            assertFalse(rows.next());
        }

        // What the driver asked Qdrant for: the vector, the limit, and the payload it needs
        assertEquals(1, server.queryRequests().size());
        assertEquals(Json.object("limit", 5, "with_payload", true, "with_vector", false,
            "query", Json.object("nearest", Json.array(0.1, 0.2, 0.3, 0.4))),
            server.queryRequests().get(0));
    }

    @Test
    void reportsASearchThatCantRunBeforeRunning() throws SQLException {
        List<String> rejected = List.of(
            "SELECT _id FROM products ORDER BY similarity(_vector, [0.1]) DESC LIMIT 1",
            "SELECT _id FROM products ORDER BY similarity(name, [0.1]) DESC LIMIT 1",
            "SELECT _id, _score FROM products LIMIT 1",
            "SELECT _id FROM products ORDER BY price DESC LIMIT 1");

        for (String sql : rejected) {
            assertThrows(SQLException.class, () -> statement.executeQuery(sql), sql);
        }

        assertEquals(List.of(), server.queryRequests(), "none of them reached Qdrant");
    }

    @Test
    void countsThePointsOfACollection() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM products")) {
            assertEquals(1, rows.getMetaData().getColumnCount());
            assertEquals("count", rows.getMetaData().getColumnLabel(1));
            assertEquals(Types.BIGINT, rows.getMetaData().getColumnType(1));
            assertSame(statement, rows.getStatement());

            assertTrue(rows.next());
            assertEquals(1L, rows.getLong(1));
            assertFalse(rows.next());
        }

        // One call, which asks for an exact count, and no read of the collection's columns
        assertEquals(List.of(Json.object("exact", true)), server.countRequests());
        assertEquals(0, reads("products"));
    }

    @Test
    void countsUnderTheNameTheStatementGivesTheCount() throws SQLException {
        try (ResultSet rows = statement.executeQuery(
            "select count(*) as total from collections.products t")) {
            assertEquals("total", rows.getMetaData().getColumnLabel(1));
            assertTrue(rows.next());
            assertEquals(1L, rows.getLong("total"));
        }
    }

    @Test
    void returnsNoRowAndCountsNothingForALimitOfNoRows() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM products LIMIT 0")) {
            assertEquals("count", rows.getMetaData().getColumnLabel(1));
            assertFalse(rows.next());
        }

        assertEquals(List.of(), server.countRequests());
    }

    @Test
    void reportsACountThatCantRunBeforeRunning() {
        List<String> rejected = List.of(
            "SELECT COUNT(price) FROM products",
            "SELECT COUNT(DISTINCT price) FROM products",
            "SELECT SUM(price) FROM products",
            "SELECT COUNT(*), name FROM products",
            "SELECT COUNT(*) FROM system.collections",
            "SELECT name, COUNT(*) FROM products GROUP BY name");

        for (String sql : rejected) {
            assertThrows(SQLException.class, () -> statement.executeQuery(sql), sql);
        }

        assertEquals(List.of(), server.countRequests(), "none of them reached Qdrant");
    }

    /**
     * The statement DataGrip's data editor sends when a collection is opened: the star and the
     * columns are named under the name it gives the collection in FROM.
     */
    @Test
    void readsACollectionNamedUnderAnAlias() throws SQLException {
        try (ResultSet rows = statement.executeQuery(
            "SELECT t.* FROM collections.products t")) {
            assertEquals(List.of("_id", "name", "price", "_payload"),
                List.of(rows.getMetaData().getColumnLabel(1), rows.getMetaData().getColumnLabel(2),
                    rows.getMetaData().getColumnLabel(3), rows.getMetaData().getColumnLabel(4)));

            assertTrue(rows.next());
            assertEquals("1", rows.getString("_id"));
            assertEquals("Lamp", rows.getString("name"));
            assertFalse(rows.next());
        }

        try (ResultSet rows = statement.executeQuery(
            "SELECT t.name, t.price AS cost FROM products AS t LIMIT 1")) {
            assertTrue(rows.next());
            assertEquals("Lamp", rows.getString("name"));
            assertEquals(39.9, rows.getDouble("cost"));
        }
    }

    @Test
    void reportsAQualifierThatNamesNoTableBeforeRunning() throws SQLException {
        SQLException error = assertThrows(SQLException.class,
            () -> statement.executeQuery("SELECT x.name FROM products t"));

        assertEquals("42S02", error.getSQLState());
        assertEquals("Unknown table or alias 'x' in 'x.name': this statement reads products as t",
            error.getMessage());
    }

    @Test
    void countsTheRowsPerGroup() throws SQLException {
        server.collection("tagged", FakeQdrantServer.unnamedVector(2, "Cosine",
                Map.of("lang", "keyword")),
            List.of(point(1, Json.object("lang", "en")), point(2, Json.object("lang", "en")),
                point(3, Json.object("lang", "de"))));

        try (ResultSet rows = statement.executeQuery(
            "SELECT lang, COUNT(*) AS n FROM tagged GROUP BY lang LIMIT 9")) {
            assertEquals(List.of("lang", "n"), List.of(rows.getMetaData().getColumnLabel(1),
                rows.getMetaData().getColumnLabel(2)));
            assertEquals(Types.BIGINT, rows.getMetaData().getColumnType(2));

            assertTrue(rows.next());
            assertEquals("en", rows.getString("lang"));
            assertEquals(2L, rows.getLong("n"), "the most common value first");
            assertTrue(rows.next());
            assertEquals("de", rows.getString("lang"));
            assertEquals(1L, rows.getLong("n"));
            assertFalse(rows.next());
        }

        assertEquals(Json.object("key", "lang", "limit", 9, "exact", true),
            server.facetRequests().get(0));
    }

    /**
     * Which points are counted is Qdrant's to decide, so what this checks is the filter the
     * driver sent: the fake server counts every point whatever the filter says.
     */
    @Test
    void sendsTheFilterOfAGroupedStatement() throws SQLException {
        server.collection("tagged", FakeQdrantServer.unnamedVector(2, "Cosine",
            Map.of("lang", "keyword")), List.of(point(1, Json.object("lang", "en"))));

        statement.executeQuery("SELECT lang, COUNT(*) FROM tagged WHERE lang = 'en' "
            + "GROUP BY lang LIMIT 9").close();

        assertEquals(Json.object("key", "lang", "limit", 9, "exact", true, "filter",
            Json.object("must", Json.array(
                Json.object("key", "lang", "match", Json.object("value", "en"))))),
            server.facetRequests().get(0));
    }

    @Test
    void countsThePointsAFilterMatches() throws SQLException {
        try (ResultSet rows = statement.executeQuery(
            "SELECT COUNT(*) FROM products WHERE name = 'Lamp'")) {
            assertTrue(rows.next());
            assertEquals(1L, rows.getLong("count"));
        }

        assertEquals(Json.object("exact", true, "filter", Json.object("must", Json.array(
            Json.object("key", "name", "match", Json.object("value", "Lamp"))))),
            server.countRequests().get(0));
    }

    @Test
    void reportsAGroupedStatementThatCantRunBeforeRunning() throws SQLException {
        // price has no index Qdrant can count values of, and the second says no limit
        for (String sql : List.of("SELECT price, COUNT(*) FROM products GROUP BY price LIMIT 1",
            "SELECT name, COUNT(*) FROM products GROUP BY name")) {
            assertThrows(SQLException.class, () -> statement.executeQuery(sql), sql);
        }

        assertEquals(List.of(), server.facetRequests(), "neither reached Qdrant");
    }

    /**
     * The warning the driver's own limit raises reaches the statement, which is how JDBC says a
     * result is correct but not the whole answer.
     */
    @Test
    void warnsWhenItsOwnLimitBoundedTheRead() throws SQLException {
        server.collection("ordered", FakeQdrantServer.unnamedVector(2, "Cosine",
                Map.of("price", "float")),
            List.of(point(1, Json.object("price", 9.5)), point(2, Json.object("price", 1.5))));

        try (ResultSet rows = statement.executeQuery(
            "SELECT _id FROM ordered ORDER BY price DESC")) {
            assertTrue(rows.next());
        }

        SQLWarning warning = statement.getWarnings();
        assertNotNull(warning, "the read was bounded by the connection's default limit");
        assertEquals("01000", warning.getSQLState());
        assertTrue(warning.getMessage().contains("defaultLimit"), warning.getMessage());
        assertEquals(1000, lastScroll().path("limit").asInt(), "the default limit");

        statement.clearWarnings();
        assertNull(statement.getWarnings());

        // A statement that says how many rows it wants is warned about nothing
        statement.executeQuery("SELECT _id FROM ordered ORDER BY price DESC LIMIT 1").close();
        assertNull(statement.getWarnings());
    }

    @Test
    void readsTheRowsOfASystemTable() throws SQLException {
        server.alias("current", "products");

        try (ResultSet rows = statement.executeQuery(
            "SELECT name, points_count, status FROM system.collections")) {
            assertEquals(List.of("name", "points_count", "status"),
                List.of(rows.getMetaData().getColumnLabel(1), rows.getMetaData().getColumnLabel(2),
                    rows.getMetaData().getColumnLabel(3)));
            assertSame(statement, rows.getStatement());

            assertTrue(rows.next());
            assertEquals("products", rows.getString("name"));
            assertEquals(42L, rows.getLong("points_count"));
            assertEquals("green", rows.getString("status"));
            assertFalse(rows.next());
        }

        try (ResultSet rows = statement.executeQuery("SELECT * FROM qdrant.system.aliases")) {
            assertTrue(rows.next());
            assertEquals("current", rows.getString("alias_name"));
            assertEquals("products", rows.getString("collection_name"));
            assertFalse(rows.next());
        }
    }

    @Test
    void reportsAnUnknownSystemTableOrColumnBeforeRunning() throws SQLException {
        SQLSyntaxErrorException table = assertThrows(SQLSyntaxErrorException.class,
            () -> statement.executeQuery("SELECT * FROM system.queries"));
        assertEquals("42S02", table.getSQLState());

        SQLSyntaxErrorException column = assertThrows(SQLSyntaxErrorException.class,
            () -> statement.executeQuery("SELECT points FROM system.collections"));
        assertEquals("42S22", column.getSQLState());

        // A system table's columns are the driver's own, so none of this read any collection
        assertEquals(0, reads("products"));
    }

    @Test
    void runsASelectFromACollectionInEveryExecute() throws SQLException {
        List<Executable> calls = List.of(
            () -> assertTrue(statement.execute(SQL)),
            () -> assertTrue(statement.execute(SQL, Statement.RETURN_GENERATED_KEYS)),
            () -> assertTrue(statement.execute(SQL, new int[] {1})),
            () -> assertTrue(statement.execute(SQL, new String[] {"id"})));

        for (Executable call : calls) {
            assertDoesNotThrow(call);
            assertTrue(statement.getResultSet().next());
        }
    }

    @Test
    void readsPagesOfTheFetchSizeUpToTheMaximumRows() throws SQLException {
        server.collection("products", unnamedVector(4, "Cosine", Map.of()), List.of(
            point(1, Json.object("name", "a")), point(2, Json.object("name", "b")),
            point(3, Json.object("name", "c")), point(4, Json.object("name", "d")),
            point(5, Json.object("name", "e"))));
        statement.setFetchSize(2);
        statement.setMaxRows(3);

        try (ResultSet rows = statement.executeQuery("SELECT name FROM products")) {
            List<String> names = new java.util.ArrayList<>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            assertEquals(List.of("a", "b", "c"), names);
        }

        assertEquals(List.of(2, 1), server.scrollRequests().stream()
            .skip(1)  // the first scroll is the column sample, read when the SQL is planned
            .map(request -> request.path("limit").asInt())
            .toList());
    }

    @Test
    void reportsACollectionThatDoesNotExist() {
        SQLException error = assertThrows(SQLException.class,
            () -> statement.executeQuery("SELECT * FROM missing"));

        assertEquals("42S02", error.getSQLState());
    }

    @Test
    void readsKeptColumnsOnlyOnce() throws SQLException {
        statement.executeQuery("SELECT name FROM products");
        statement.executeQuery("SELECT price FROM products");

        assertEquals(1, reads("products"));
    }

    @Test
    void findsAFieldAddedSinceTheColumnsWereKept() throws SQLException {
        statement.executeQuery("SELECT name FROM products");
        server.collection("products", unnamedVector(4, "Cosine", Map.of()),
            List.of(point(1, Json.object("name", "Lamp", "price", 39.9, "color", "red"))));

        // Not "unknown column": the kept columns are read again when color isn't there
        try (ResultSet rows = statement.executeQuery("SELECT color FROM products")) {
            assertTrue(rows.next());
            assertEquals("red", rows.getString("color"));
        }
        assertEquals(2, reads("products"));
    }

    @Test
    void reportsAnUnknownColumnAfterReadingTheColumnsOnceMore() {
        SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class,
            () -> statement.executeQuery("SELECT nope FROM products"));

        assertEquals("42S22", error.getSQLState());
        assertEquals("Unknown column 'nope' in products", error.getMessage());
        assertEquals(2, reads("products"));
    }

    @Test
    void reportsSyntaxErrorsBeforeSayingSqlCantRunYet() {
        String broken = "SELECT * FROM products WHERE price >";
        List<Executable> calls = List.of(
            () -> statement.executeQuery(broken),
            () -> statement.execute(broken),
            () -> statement.execute(broken, Statement.RETURN_GENERATED_KEYS),
            () -> statement.executeUpdate(broken),
            () -> statement.executeLargeUpdate(broken, new String[] {"id"}));

        for (Executable call : calls) {
            SQLSyntaxErrorException error = assertThrows(SQLSyntaxErrorException.class, call);
            assertEquals("Expected a value, found the end of the statement at line 1, column 37",
                error.getMessage());
        }
    }

    @Test
    void returnsTheRowOfASelectWithoutFrom() throws SQLException {
        try (ResultSet rows = statement.executeQuery("select 'keep alive'")) {
            assertEquals(1, rows.getMetaData().getColumnCount());
            assertEquals("?column?", rows.getMetaData().getColumnLabel(1));
            assertTrue(rows.next());
            assertEquals("keep alive", rows.getString(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void namesAndTypesTheColumnsOfConstants() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT 1 AS one, 2.5 AS half, true AS yes, 'x'")) {
            ResultSetMetaData metadata = rows.getMetaData();

            assertEquals(List.of("one", "half", "yes", "?column?"), List.of(metadata.getColumnLabel(1),
                metadata.getColumnLabel(2), metadata.getColumnLabel(3), metadata.getColumnLabel(4)));
            assertEquals(List.of(Types.BIGINT, Types.DOUBLE, Types.BOOLEAN, Types.VARCHAR), List.of(
                metadata.getColumnType(1), metadata.getColumnType(2), metadata.getColumnType(3),
                metadata.getColumnType(4)));

            assertTrue(rows.next());
            assertEquals(1, rows.getLong("one"));
            assertEquals(2.5, rows.getDouble("half"));
            assertTrue(rows.getBoolean("yes"));
            assertEquals("x", rows.getString(4));
        }
    }

    @Test
    void returnsNoRowsWhenLimitOrOffsetSkipsTheRow() throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT 1 LIMIT 0")) {
            assertFalse(rows.next());
        }
        try (ResultSet rows = statement.executeQuery("SELECT 1 OFFSET 1")) {
            assertFalse(rows.next());
        }
    }

    @Test
    void keepsTheResultOfExecuteUntilTheNextResultIsAskedFor() throws SQLException {
        assertTrue(statement.execute("SELECT 1"));

        ResultSet rows = statement.getResultSet();
        assertNotNull(rows);
        assertSame(statement, rows.getStatement());
        assertEquals(-1, statement.getUpdateCount());

        assertFalse(statement.getMoreResults());
        assertTrue(rows.isClosed());
        assertNull(statement.getResultSet());
    }

    @Test
    void closesThePreviousResultOnTheNextRun() throws SQLException {
        ResultSet first = statement.executeQuery("SELECT 1");
        ResultSet second = statement.executeQuery("SELECT 2");

        assertTrue(first.isClosed());
        assertFalse(second.isClosed());
        assertSame(second, statement.getResultSet());
    }

    @Test
    void closesItsResultWhenClosed() throws SQLException {
        ResultSet rows = statement.executeQuery("SELECT 1");
        statement.close();

        assertTrue(rows.isClosed());
    }

    @Test
    void refusesASelectInExecuteUpdate() {
        SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate("SELECT 1"));

        assertFalse(error instanceof SQLFeatureNotSupportedException);
        assertTrue(error.getMessage().contains("executeUpdate can't run a SELECT"), error.getMessage());
    }

    @Test
    void reportsUnknownColumnsInASelectWithoutFrom() {
        SQLSyntaxErrorException error =
            assertThrows(SQLSyntaxErrorException.class, () -> statement.executeQuery("SELECT name"));

        assertEquals("42S22", error.getSQLState());
    }

    @Test
    void reportsEmptySqlAsASyntaxError() {
        assertThrows(SQLSyntaxErrorException.class, () -> statement.executeQuery(""));
        assertThrows(SQLSyntaxErrorException.class, () -> statement.executeQuery("   "));
    }

    @Test
    void rejectsNullSql() {
        List<Executable> calls = List.of(
            () -> statement.executeQuery(null),
            () -> statement.execute(null),
            () -> statement.executeUpdate(null));

        for (Executable call : calls) {
            assertEquals("HY009", assertThrows(SQLException.class, call).getSQLState());
        }
    }

    @Test
    void doesNotSupportBatchesOrCursorNames() {
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.addBatch(SQL));
        assertThrows(SQLFeatureNotSupportedException.class, statement::clearBatch);
        assertThrows(SQLFeatureNotSupportedException.class, statement::executeBatch);
        assertThrows(SQLFeatureNotSupportedException.class, statement::executeLargeBatch);
        assertThrows(SQLFeatureNotSupportedException.class, () -> statement.setCursorName("c"));
    }

    @Test
    void hasNoGeneratedKeysUntilAnInsertAddsPoints() throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            assertEquals("_id", keys.getMetaData().getColumnName(1));
            assertFalse(keys.next());
        }
    }

    @Test
    void hasNoResultsBeforeRunningAnything() throws SQLException {
        assertNull(statement.getResultSet());
        assertEquals(-1, statement.getUpdateCount());
        assertEquals(-1, statement.getLargeUpdateCount());
        assertFalse(statement.getMoreResults());
        assertFalse(statement.getMoreResults(Statement.CLOSE_ALL_RESULTS));
        assertNull(statement.getWarnings());
        assertDoesNotThrow(statement::clearWarnings);
        assertDoesNotThrow(statement::cancel);
    }

    @Test
    void keepsTheSettingsToolsApply() throws SQLException {
        statement.setMaxRows(500);
        statement.setMaxFieldSize(1024);
        statement.setFetchSize(100);
        statement.setQueryTimeout(30);
        statement.setPoolable(true);
        statement.setEscapeProcessing(false);
        statement.closeOnCompletion();

        assertEquals(500, statement.getMaxRows());
        assertEquals(500, statement.getLargeMaxRows());
        assertEquals(1024, statement.getMaxFieldSize());
        assertEquals(100, statement.getFetchSize());
        assertEquals(30, statement.getQueryTimeout());
        assertTrue(statement.isPoolable());
        assertTrue(statement.isCloseOnCompletion());
    }

    @Test
    void capsLargeMaxRowsForTheIntGetter() throws SQLException {
        statement.setLargeMaxRows(5_000_000_000L);

        assertEquals(5_000_000_000L, statement.getLargeMaxRows());
        assertEquals(Integer.MAX_VALUE, statement.getMaxRows());
    }

    @Test
    void acceptsEveryFetchDirectionAsAHint() throws SQLException {
        for (int direction : new int[] {ResultSet.FETCH_REVERSE, ResultSet.FETCH_UNKNOWN,
            ResultSet.FETCH_FORWARD}) {
            statement.setFetchDirection(direction);
            assertEquals(direction, statement.getFetchDirection());
        }

        assertEquals("HY024",
            assertThrows(SQLException.class, () -> statement.setFetchDirection(42)).getSQLState());
    }

    @Test
    void rejectsNegativeSettings() {
        List<Executable> calls = List.of(
            () -> statement.setMaxRows(-1),
            () -> statement.setLargeMaxRows(-1),
            () -> statement.setMaxFieldSize(-1),
            () -> statement.setFetchSize(-1),
            () -> statement.setQueryTimeout(-1));

        for (Executable call : calls) {
            assertEquals("HY024", assertThrows(SQLException.class, call).getSQLState());
        }
    }

    @Test
    void describesItsResultSets() throws SQLException {
        assertEquals(ResultSet.TYPE_FORWARD_ONLY, statement.getResultSetType());
        assertEquals(ResultSet.CONCUR_READ_ONLY, statement.getResultSetConcurrency());
        assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, statement.getResultSetHoldability());
    }

    @Test
    void belongsToItsConnection() throws SQLException {
        assertSame(connection, statement.getConnection());
    }

    @Test
    void refusesWorkOnceClosed() throws SQLException {
        statement.close();
        statement.close();

        assertTrue(statement.isClosed());
        assertEquals("HY010", assertThrows(SQLException.class, statement::getMaxRows).getSQLState());
        assertEquals("HY010",
            assertThrows(SQLException.class, () -> statement.executeQuery(SQL)).getSQLState());
    }

    @Test
    void refusesWorkOnceTheConnectionIsClosed() throws SQLException {
        connection.close();

        assertEquals("08003", assertThrows(SQLException.class, statement::getMaxRows).getSQLState());
    }

    @Test
    void isClosedOnceTheConnectionIsClosed() throws SQLException {
        assertFalse(statement.isClosed());

        connection.close();

        assertTrue(statement.isClosed());
        assertDoesNotThrow(statement::close);
    }

    // DataGrip cancels from another thread when the user stops a query, often after it finished
    @Test
    void cancelsWithoutErrorEvenOnceClosed() throws SQLException {
        Statement other = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT 1");

        assertDoesNotThrow(statement::cancel);
        assertFalse(rows.isClosed(), "cancelling a finished statement leaves its result alone");

        statement.close();
        assertDoesNotThrow(statement::cancel);

        connection.close();
        assertDoesNotThrow(other::cancel);
    }

    @Test
    void unwrapsToItself() throws SQLException {
        assertTrue(statement.isWrapperFor(QdrantStatement.class));
        assertFalse(statement.isWrapperFor(Connection.class));
        assertSame(statement, statement.unwrap(Statement.class));
        assertThrows(SQLException.class, () -> statement.unwrap(Connection.class));
    }

    /**
     * The last scroll the server received: reading a collection's columns scrolls a sample of
     * its points first, so a statement's own read is the one after that.
     */
    private JsonNode lastScroll() {
        List<JsonNode> scrolls = server.scrollRequests();

        return scrolls.get(scrolls.size() - 1);
    }

    // How many times the collection's configuration was read, once per schema read
    private long reads(String collection) {
        return server.requests().stream()
            .filter(request -> request.method().equals("GET")
                && request.path().equals("/collections/" + collection))
            .count();
    }
}
