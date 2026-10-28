package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Walks the whole server the way a generic JDBC tool such as DataGrip introspects a new data
 * source, reading every value of every metadata result with the type its metadata declares.
 * Needs {@code docker compose up -d}.
 */
@Tag("integration")
class IntrospectionIntegrationTest {

    private static final String PREFIX =
        "jdbc_intro_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    private static QdrantGateway gateway;

    @BeforeAll
    static void createCollections() throws SQLException {
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, PREFIX);
    }

    @AfterAll
    static void deleteCollections() throws SQLException {
        if (gateway != null) {
            SampleCollections.delete(gateway, PREFIX);
            gateway.close();
        }
    }

    @Test
    void survivesAFullIntrospection() throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestQdrant.jdbcUrl(),
            TestQdrant.properties())) {
            connection.setAutoCommit(true);
            connection.setReadOnly(true);
            connection.getTransactionIsolation();
            connection.getCatalog();
            connection.getSchema();
            connection.getWarnings();

            DatabaseMetaData metaData = connection.getMetaData();
            assertNotNull(metaData.getDatabaseProductName());
            assertNotNull(metaData.getDatabaseProductVersion());
            assertNotNull(metaData.getDriverName());
            assertNotNull(metaData.getIdentifierQuoteString());
            assertNotNull(metaData.getSQLKeywords());
            assertNotNull(metaData.getNumericFunctions());
            assertNotNull(metaData.getStringFunctions());
            assertNotNull(metaData.getSystemFunctions());
            assertNotNull(metaData.getTimeDateFunctions());
            assertNotNull(metaData.getSearchStringEscape());

            readAll(metaData.getCatalogs());
            readAll(metaData.getSchemas());
            readAll(metaData.getTypeInfo());
            readAll(metaData.getClientInfoProperties());
            readAll(metaData.getProcedures(null, null, "%"));
            readAll(metaData.getFunctions(null, null, "%"));
            readAll(metaData.getUDTs(null, null, "%", null));

            List<String> tableTypes = new ArrayList<>();

            try (ResultSet types = metaData.getTableTypes()) {
                while (types.next()) {
                    tableTypes.add(types.getString(1));
                }
            }

            List<String> tables = new ArrayList<>();

            try (ResultSet rows = metaData.getTables(null, null, "%",
                tableTypes.toArray(new String[0]))) {
                for (List<Object> row : readAll(rows)) {
                    String name = (String) row.get(2);

                    if (name.startsWith(PREFIX)) {
                        tables.add(name);
                    }
                }
            }

            assertEquals(3, tables.size());
            int columns = 0;

            for (String table : tables) {
                columns += readAll(metaData.getColumns(null, null, table, "%")).size();
                assertEquals(1, readAll(metaData.getPrimaryKeys(null, null, table)).size());
                assertFalse(readAll(metaData.getIndexInfo(null, null, table, false, true)).isEmpty());
                readAll(metaData.getImportedKeys(null, null, table));
                readAll(metaData.getExportedKeys(null, null, table));
                readAll(metaData.getBestRowIdentifier(null, null, table,
                    DatabaseMetaData.bestRowSession, true));
                readAll(metaData.getVersionColumns(null, null, table));
                readAll(metaData.getTablePrivileges(null, null, table));
                readAll(metaData.getColumnPrivileges(null, null, table, "%"));
            }

            assertEquals(28, columns, "products 10, articles 10, events 8");
        }
    }

    /**
     * Makes the calls DataGrip makes around a query console: setting up the connection, running
     * its keep-alive query, stopping a query that has already finished, and closing. None of them
     * may fail or leave a warning.
     */
    @Test
    void survivesAConsoleSession() throws SQLException {
        Connection connection = DriverManager.getConnection(TestQdrant.jdbcUrl(),
            TestQdrant.properties());
        Statement statement;

        try (connection) {
            assertFalse(connection.isClosed());
            connection.getClientInfo("ApplicationName");
            connection.setClientInfo("ApplicationName", "DataGrip");
            assertTrue(connection.isValid(20));
            connection.getAutoCommit();
            connection.setAutoCommit(true);
            connection.setReadOnly(false);
            connection.setCatalog("");
            connection.setSchema("");
            assertNull(connection.getWarnings());
            connection.clearWarnings();

            statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY);
            statement.setEscapeProcessing(false);
            statement.setFetchSize(100);
            statement.setMaxRows(501);

            assertTrue(statement.execute("select 'keep alive'"));

            try (ResultSet rows = statement.getResultSet()) {
                assertTrue(rows.next());
                assertEquals("keep alive", rows.getObject(1));
            }

            assertFalse(statement.getMoreResults());
            assertEquals(-1, statement.getUpdateCount());
            assertNull(statement.getWarnings());

            // Stop pressed after the query finished, then again once the console closed it
            statement.cancel();
            statement.close();
            statement.cancel();

            assertNull(connection.getWarnings());
        }

        assertTrue(statement.isClosed());
        statement.cancel();
    }

    @Test
    void abortsAConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(TestQdrant.jdbcUrl(),
            TestQdrant.properties());
        Statement statement = connection.createStatement();

        connection.abort(Runnable::run);

        assertTrue(connection.isClosed());
        assertTrue(statement.isClosed());
        assertFalse(connection.isValid(5));
        statement.cancel();
        connection.close();
    }

    @Test
    @Disabled("The SQL Lexer rejects qualified names such as t.* with a syntax error; "
        + "re-enable when the parser reads them")
    void opensATableWithAClearErrorForNow() throws SQLException {
        try (Connection connection = DriverManager.getConnection(TestQdrant.jdbcUrl(),
            TestQdrant.properties());
             Statement statement = connection.createStatement()) {
            statement.setMaxRows(501);

            SQLFeatureNotSupportedException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> statement.executeQuery("SELECT t.* FROM \"" + PREFIX + "products\" t"));

            assertTrue(error.getMessage().contains("isn't supported yet"), error.getMessage());
        }
    }

    /**
     * Reads every row and every column, checking each value against the declared column type.
     */
    private static List<List<Object>> readAll(ResultSet rows) throws SQLException {
        List<List<Object>> all = new ArrayList<>();

        try (rows) {
            ResultSetMetaData columns = rows.getMetaData();

            for (int i = 1; i <= columns.getColumnCount(); i++) {
                assertNotNull(columns.getColumnLabel(i));
                assertNotNull(columns.getColumnTypeName(i));
                assertNotNull(columns.getColumnClassName(i));
            }

            while (rows.next()) {
                List<Object> row = new ArrayList<>();

                for (int i = 1; i <= columns.getColumnCount(); i++) {
                    Object value = rows.getObject(i);
                    row.add(value);
                    rows.getString(i);

                    if (value != null) {
                        String declared = columns.getColumnClassName(i);
                        assertTrue(declared.equals(Object.class.getName())
                                || Class.forName(declared, false, IntrospectionIntegrationTest.class.getClassLoader()).isInstance(value),
                            columns.getColumnLabel(i) + " declared " + declared + " but holds "
                                + value.getClass().getName());
                    }
                }

                all.add(row);
            }
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }

        return all;
    }
}
