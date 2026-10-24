package com.runaxsoftware.qdrant.jdbc;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import io.grpc.Status;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

class QdrantDatabaseMetaDataTest {

    private FakeQdrantServer server;
    private Connection connection;
    private DatabaseMetaData metaData;

    @BeforeEach
    void connect() throws Exception {
        Map<String, PayloadSchemaType> productIndexes = new LinkedHashMap<>();
        productIndexes.put("price", PayloadSchemaType.Float);
        productIndexes.put("name", PayloadSchemaType.Keyword);

        server = FakeQdrantServer.start()
            .collection("products", FakeQdrantServer.unnamedVector(4, Distance.Cosine, productIndexes),
                List.of(FakeQdrantServer.point(id(1), Map.of(
                    "name", value("Lamp"), "price", value(19.5), "count", value(3)))))
            .collection("Mixed-Case", FakeQdrantServer.unnamedVector(2, Distance.Dot, Map.of()),
                List.of())
            .collection("articles", FakeQdrantServer.collectionInfo(
                Collections.VectorsConfig.newBuilder()
                    .setParamsMap(Collections.VectorParamsMap.newBuilder()
                        .putMap("dense", VectorParams.newBuilder().setSize(3)
                            .setDistance(Distance.Euclid).build()))
                    .build(),
                Collections.SparseVectorConfig.newBuilder()
                    .putMap("keywords", Collections.SparseVectorParams.getDefaultInstance())
                    .build(),
                Map.of()), List.of())
            .listedButMissing("ghost");
        connection = new QdrantDriver().connect(server.jdbcUrl(), new Properties());
        metaData = connection.getMetaData();
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
        server.close();
    }

    // Tables

    @Test
    void listsCollectionsSortedByName() throws SQLException {
        assertEquals(List.of("Mixed-Case", "articles", "ghost", "products"),
            column(metaData.getTables(null, null, null, null), "TABLE_NAME"));
    }

    @Test
    void reportsTablesWithJdbcColumns() throws SQLException {
        try (ResultSet tables = metaData.getTables(null, null, "products", null)) {
            assertEquals(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"),
                labels(tables));
            assertTrue(tables.next());
            assertNull(tables.getString("TABLE_CAT"));
            assertNull(tables.getString("TABLE_SCHEM"));
            assertEquals("products", tables.getString("TABLE_NAME"));
            assertEquals("TABLE", tables.getString("TABLE_TYPE"));
            assertFalse(tables.next());
        }
    }

    @Test
    void filtersTablesByCaseSensitivePattern() throws SQLException {
        assertEquals(List.of("products"), tables("pro%", null));
        assertEquals(List.of(), tables("PRO%", null));
        assertEquals(List.of("Mixed-Case"), tables("Mixed\\-Case", null));
        assertEquals(List.of("Mixed-Case"), tables("Mixed_Case", null));
    }

    @Test
    void filtersTablesByType() throws SQLException {
        assertEquals(4, tables("%", new String[] {"TABLE"}).size());
        assertEquals(4, tables("%", new String[] {"VIEW", "table"}).size());
        assertEquals(List.of(), tables("%", new String[] {"VIEW", "SYSTEM TABLE"}));
        assertEquals(List.of(), tables("%", new String[0]));
    }

    @Test
    void matchesOnlyFiltersWithoutCatalogOrSchema() throws SQLException {
        assertEquals(4, column(metaData.getTables("", "", "%", null), "TABLE_NAME").size());
        assertEquals(4, column(metaData.getTables(null, "%", "%", null), "TABLE_NAME").size());
        assertEquals(List.of(), column(metaData.getTables("qdrant", null, "%", null), "TABLE_NAME"));
        assertEquals(List.of(), column(metaData.getTables(null, "public", "%", null), "TABLE_NAME"));
    }

    // Columns

    @Test
    void describesColumnsWithJdbcColumns() throws SQLException {
        try (ResultSet columns = metaData.getColumns(null, null, "products", "%")) {
            assertEquals(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME",
                "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS",
                "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
                "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE",
                "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN"), labels(columns));
        }
    }

    @Test
    void describesEachColumnOfACollection() throws SQLException {
        List<String> described = new ArrayList<>();

        try (ResultSet columns = metaData.getColumns(null, null, "products", "%")) {
            while (columns.next()) {
                Integer size = columns.getObject("COLUMN_SIZE", Integer.class);
                Integer radix = columns.getObject("NUM_PREC_RADIX", Integer.class);
                described.add(columns.getInt("ORDINAL_POSITION") + " "
                    + columns.getString("COLUMN_NAME") + " "
                    + columns.getInt("DATA_TYPE") + " "
                    + columns.getString("TYPE_NAME") + " "
                    + size + " " + radix + " "
                    + columns.getString("IS_NULLABLE"));
                assertEquals("NO", columns.getString("IS_AUTOINCREMENT"));
                assertEquals("NO", columns.getString("IS_GENERATEDCOLUMN"));
                assertNull(columns.getString("COLUMN_DEF"));
            }
        }

        assertEquals(List.of(
            "1 id " + Types.VARCHAR + " VARCHAR null null NO",
            "2 count " + Types.BIGINT + " BIGINT 19 10 YES",
            "3 name " + Types.VARCHAR + " VARCHAR null null YES",
            "4 price " + Types.DOUBLE + " DOUBLE 17 10 YES",
            "5 payload " + Types.VARCHAR + " JSON null null YES",
            "6 vector " + Types.VARCHAR + " VECTOR 4 null YES"), described);
    }

    @Test
    void putsDescriptionsInRemarks() throws SQLException {
        Map<String, String> remarks = new LinkedHashMap<>();

        try (ResultSet columns = metaData.getColumns(null, null, "articles", "%")) {
            while (columns.next()) {
                remarks.put(columns.getString("COLUMN_NAME"), columns.getString("REMARKS"));
            }
        }

        assertEquals("Point ID: an unsigned integer or a UUID", remarks.get("id"));
        assertEquals("The whole payload as JSON", remarks.get("payload"));
        assertEquals("Dense vector, 3 dimensions, Euclid distance", remarks.get("dense"));
        assertEquals("Sparse vector", remarks.get("keywords"));
    }

    @Test
    void filtersColumnsByTableAndColumnPattern() throws SQLException {
        assertEquals(List.of("name"), column(metaData.getColumns(null, null, "products", "n%"),
            "COLUMN_NAME"));
        assertEquals(List.of("Mixed-Case", "articles", "products"),
            column(metaData.getColumns(null, null, "%", "id"), "TABLE_NAME"));
        assertEquals(List.of(), column(metaData.getColumns("qdrant", null, "%", "%"),
            "COLUMN_NAME"));
    }

    @Test
    void skipsCollectionsThatDisappearWhileReading() throws SQLException {
        List<String> tables = column(metaData.getColumns(null, null, "%", "payload"), "TABLE_NAME");

        assertFalse(tables.contains("ghost"));
        assertEquals(3, tables.size());
    }

    @Test
    void passesOtherErrorsOn() {
        server.fail("Get", Status.INTERNAL.withDescription("disk full"));

        SQLException error = assertThrows(SQLException.class,
            () -> metaData.getColumns(null, null, "products", "%"));

        assertEquals("HY000", error.getSQLState());
    }

    @Test
    void samplesPointsForColumns() throws SQLException {
        metaData.getColumns(null, null, "products", "%").close();

        assertEquals(1, server.scrollRequests().size());
        assertEquals(100, server.scrollRequests().get(0).getLimit());
        assertTrue(server.scrollRequests().get(0).getWithPayload().getEnable());
        assertFalse(server.scrollRequests().get(0).getWithVectors().getEnable());
    }

    @Test
    void usesOnlyPayloadIndexesWhenSamplingIsOff() throws Exception {
        try (Connection unsampled = new QdrantDriver().connect(server.jdbcUrl("sampleSize=0"),
            new Properties())) {
            assertEquals(List.of("id", "name", "price", "payload", "vector"),
                column(unsampled.getMetaData().getColumns(null, null, "products", "%"),
                    "COLUMN_NAME"));
        }

        assertTrue(server.scrollRequests().isEmpty());
    }

    // Keys and indexes

    @Test
    void reportsTheIdAsPrimaryKey() throws SQLException {
        try (ResultSet keys = metaData.getPrimaryKeys(null, null, "products")) {
            assertEquals(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ",
                "PK_NAME"), labels(keys));
            assertTrue(keys.next());
            assertEquals("products", keys.getString("TABLE_NAME"));
            assertEquals("id", keys.getString("COLUMN_NAME"));
            assertEquals(1, keys.getShort("KEY_SEQ"));
            assertEquals("products_pkey", keys.getString("PK_NAME"));
            assertFalse(keys.next());
        }

        assertEquals(List.of(), column(metaData.getPrimaryKeys(null, null, "missing"), "PK_NAME"));
        assertEquals(List.of(), column(metaData.getPrimaryKeys("qdrant", null, "products"),
            "PK_NAME"));
    }

    @Test
    void reportsPayloadIndexesAsIndexes() throws SQLException {
        try (ResultSet indexes = metaData.getIndexInfo(null, null, "products", false, true)) {
            assertEquals(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE",
                "INDEX_QUALIFIER", "INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME",
                "ASC_OR_DESC", "CARDINALITY", "PAGES", "FILTER_CONDITION"), labels(indexes));

            assertTrue(indexes.next());
            assertEquals("products_pkey", indexes.getString("INDEX_NAME"));
            assertFalse(indexes.getBoolean("NON_UNIQUE"));
            assertEquals("id", indexes.getString("COLUMN_NAME"));
            assertEquals(42, indexes.getLong("CARDINALITY"));
            assertEquals(DatabaseMetaData.tableIndexOther, indexes.getShort("TYPE"));
            assertEquals(1, indexes.getShort("ORDINAL_POSITION"));

            assertTrue(indexes.next());
            assertEquals("name_idx", indexes.getString("INDEX_NAME"));
            assertTrue(indexes.getBoolean("NON_UNIQUE"));
            assertEquals("name", indexes.getString("COLUMN_NAME"));
            indexes.getLong("CARDINALITY");
            assertTrue(indexes.wasNull());

            assertTrue(indexes.next());
            assertEquals("price_idx", indexes.getString("INDEX_NAME"));
            assertFalse(indexes.next());
        }
    }

    @Test
    void reportsOnlyThePrimaryKeyForUniqueIndexes() throws SQLException {
        assertEquals(List.of("products_pkey"),
            column(metaData.getIndexInfo(null, null, "products", true, false), "INDEX_NAME"));
    }

    @Test
    void reportsNoIndexesForMissingCollections() throws SQLException {
        assertEquals(List.of(), column(metaData.getIndexInfo(null, null, "ghost", false, true),
            "INDEX_NAME"));
        assertEquals(List.of(), column(metaData.getIndexInfo(null, null, "missing", false, true),
            "INDEX_NAME"));
        assertEquals(List.of(), column(metaData.getIndexInfo("x", null, "products", false, true),
            "INDEX_NAME"));
    }

    // Schemas, catalogs, types

    @Test
    void hasNoCatalogsOrSchemas() throws SQLException {
        try (ResultSet schemas = metaData.getSchemas();
             ResultSet filtered = metaData.getSchemas(null, "%");
             ResultSet catalogs = metaData.getCatalogs()) {
            assertEquals(List.of("TABLE_SCHEM", "TABLE_CATALOG"), labels(schemas));
            assertEquals(List.of("TABLE_CAT"), labels(catalogs));
            assertFalse(schemas.next());
            assertFalse(filtered.next());
            assertFalse(catalogs.next());
        }
    }

    @Test
    void hasOneTableType() throws SQLException {
        assertEquals(List.of("TABLE"), column(metaData.getTableTypes(), "TABLE_TYPE"));
    }

    @Test
    void describesItsTypes() throws SQLException {
        List<String> names = new ArrayList<>();
        int previousType = Integer.MIN_VALUE;

        try (ResultSet types = metaData.getTypeInfo()) {
            assertEquals(List.of("TYPE_NAME", "DATA_TYPE", "PRECISION", "LITERAL_PREFIX",
                "LITERAL_SUFFIX", "CREATE_PARAMS", "NULLABLE", "CASE_SENSITIVE", "SEARCHABLE",
                "UNSIGNED_ATTRIBUTE", "FIXED_PREC_SCALE", "AUTO_INCREMENT", "LOCAL_TYPE_NAME",
                "MINIMUM_SCALE", "MAXIMUM_SCALE", "SQL_DATA_TYPE", "SQL_DATETIME_SUB",
                "NUM_PREC_RADIX"), labels(types));

            while (types.next()) {
                String name = types.getString("TYPE_NAME");
                int dataType = types.getInt("DATA_TYPE");
                names.add(name);
                assertTrue(dataType >= previousType, "sorted by DATA_TYPE");
                previousType = dataType;
                assertEquals(DatabaseMetaData.typeNullable, types.getShort("NULLABLE"));
                boolean vector = name.endsWith("VECTOR");
                assertEquals(vector ? DatabaseMetaData.typePredNone : DatabaseMetaData.typeSearchable,
                    types.getShort("SEARCHABLE"), name);
                assertEquals(dataType == Types.VARCHAR || dataType == Types.TIMESTAMP_WITH_TIMEZONE
                    ? "'" : null, types.getString("LITERAL_PREFIX"), name);
            }
        }

        assertEquals(List.of("BIGINT", "DOUBLE", "VARCHAR", "JSON", "VECTOR", "MULTIVECTOR",
            "SPARSE_VECTOR", "BOOLEAN", "TIMESTAMP WITH TIME ZONE"), names);
    }

    // Things Qdrant doesn't have

    @Test
    void returnsEmptyResultsWithJdbcColumnsForWhatQdrantLacks() throws SQLException {
        List<String> foreignKeys = List.of("PKTABLE_CAT", "PKTABLE_SCHEM", "PKTABLE_NAME",
            "PKCOLUMN_NAME", "FKTABLE_CAT", "FKTABLE_SCHEM", "FKTABLE_NAME", "FKCOLUMN_NAME",
            "KEY_SEQ", "UPDATE_RULE", "DELETE_RULE", "FK_NAME", "PK_NAME", "DEFERRABILITY");
        List<String> rowIdentifier = List.of("SCOPE", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME",
            "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "PSEUDO_COLUMN");

        assertEmpty(() -> metaData.getImportedKeys(null, null, "products"), foreignKeys);
        assertEmpty(() -> metaData.getExportedKeys(null, null, "products"), foreignKeys);
        assertEmpty(() -> metaData.getCrossReference(null, null, "products", null, null,
            "articles"), foreignKeys);
        assertEmpty(() -> metaData.getProcedures(null, null, "%"), List.of("PROCEDURE_CAT",
            "PROCEDURE_SCHEM", "PROCEDURE_NAME", "RESERVED1", "RESERVED2", "RESERVED3", "REMARKS",
            "PROCEDURE_TYPE", "SPECIFIC_NAME"));
        assertEmpty(() -> metaData.getProcedureColumns(null, null, "%", "%"), List.of(
            "PROCEDURE_CAT", "PROCEDURE_SCHEM", "PROCEDURE_NAME", "COLUMN_NAME", "COLUMN_TYPE",
            "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE",
            "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH",
            "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME"));
        assertEmpty(() -> metaData.getFunctions(null, null, "%"), List.of("FUNCTION_CAT",
            "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "FUNCTION_TYPE", "SPECIFIC_NAME"));
        assertEmpty(() -> metaData.getFunctionColumns(null, null, "%", "%"), List.of(
            "FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "COLUMN_NAME", "COLUMN_TYPE",
            "DATA_TYPE", "TYPE_NAME", "PRECISION", "LENGTH", "SCALE", "RADIX", "NULLABLE",
            "REMARKS", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SPECIFIC_NAME"));
        assertEmpty(() -> metaData.getColumnPrivileges(null, null, "products", "%"), List.of(
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "GRANTOR", "GRANTEE",
            "PRIVILEGE", "IS_GRANTABLE"));
        assertEmpty(() -> metaData.getTablePrivileges(null, null, "%"), List.of("TABLE_CAT",
            "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE"));
        assertEmpty(() -> metaData.getBestRowIdentifier(null, null, "products",
            DatabaseMetaData.bestRowSession, true), rowIdentifier);
        assertEmpty(() -> metaData.getVersionColumns(null, null, "products"), rowIdentifier);
        assertEmpty(() -> metaData.getUDTs(null, null, "%", null), List.of("TYPE_CAT",
            "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE"));
        assertEmpty(() -> metaData.getSuperTypes(null, null, "%"), List.of("TYPE_CAT",
            "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT", "SUPERTYPE_SCHEM", "SUPERTYPE_NAME"));
        assertEmpty(() -> metaData.getSuperTables(null, null, "%"), List.of("TABLE_CAT",
            "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME"));
        assertEmpty(() -> metaData.getAttributes(null, null, "%", "%"), List.of("TYPE_CAT",
            "TYPE_SCHEM", "TYPE_NAME", "ATTR_NAME", "DATA_TYPE", "ATTR_TYPE_NAME", "ATTR_SIZE",
            "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "ATTR_DEF", "SQL_DATA_TYPE",
            "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE",
            "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE"));
        assertEmpty(metaData::getClientInfoProperties, List.of("NAME", "MAX_LEN", "DEFAULT_VALUE",
            "DESCRIPTION"));
        assertEmpty(() -> metaData.getPseudoColumns(null, null, "%", "%"), List.of("TABLE_CAT",
            "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "COLUMN_SIZE",
            "DECIMAL_DIGITS", "NUM_PREC_RADIX", "COLUMN_USAGE", "REMARKS", "CHAR_OCTET_LENGTH",
            "IS_NULLABLE"));
    }

    // Product, driver, and capabilities

    @Test
    void describesTheProductAndDriver() throws SQLException {
        assertEquals("Qdrant", metaData.getDatabaseProductName());
        assertEquals(FakeQdrantServer.VERSION, metaData.getDatabaseProductVersion());
        assertEquals(1, metaData.getDatabaseMajorVersion());
        assertEquals(20, metaData.getDatabaseMinorVersion());
        assertEquals(DriverInfo.NAME, metaData.getDriverName());
        assertEquals(DriverInfo.VERSION, metaData.getDriverVersion());
        assertEquals(DriverInfo.MAJOR_VERSION, metaData.getDriverMajorVersion());
        assertEquals(DriverInfo.MINOR_VERSION, metaData.getDriverMinorVersion());
        assertEquals(4, metaData.getJDBCMajorVersion());
        assertEquals(2, metaData.getJDBCMinorVersion());
        assertEquals(server.jdbcUrl(), metaData.getURL());
        assertNull(metaData.getUserName());
        assertSame(connection, metaData.getConnection());
        assertTrue(metaData.isReadOnly());
    }

    @Test
    void describesIdentifiers() throws SQLException {
        assertEquals("\"", metaData.getIdentifierQuoteString());
        assertEquals("\\", metaData.getSearchStringEscape());
        assertEquals("", metaData.getExtraNameCharacters());
        assertTrue(metaData.supportsMixedCaseIdentifiers());
        assertTrue(metaData.supportsMixedCaseQuotedIdentifiers());
        assertFalse(metaData.storesUpperCaseIdentifiers());
        assertFalse(metaData.storesLowerCaseIdentifiers());
        assertFalse(metaData.storesMixedCaseIdentifiers());
        assertFalse(metaData.supportsSchemasInDataManipulation());
        assertFalse(metaData.supportsCatalogsInDataManipulation());
    }

    @Test
    void describesResultSetsAndTransactions() throws SQLException {
        assertTrue(metaData.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY));
        assertFalse(metaData.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE));
        assertTrue(metaData.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_READ_ONLY));
        assertFalse(metaData.supportsResultSetConcurrency(ResultSet.TYPE_FORWARD_ONLY,
            ResultSet.CONCUR_UPDATABLE));
        assertTrue(metaData.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT));
        assertFalse(metaData.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT));
        assertFalse(metaData.supportsTransactions());
        assertEquals(Connection.TRANSACTION_NONE, metaData.getDefaultTransactionIsolation());
        assertTrue(metaData.supportsTransactionIsolationLevel(Connection.TRANSACTION_NONE));
        assertFalse(metaData.supportsTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE));
        assertEquals(DatabaseMetaData.sqlStateSQL, metaData.getSQLStateType());
        assertEquals(RowIdLifetime.ROWID_UNSUPPORTED, metaData.getRowIdLifetime());
        assertEquals(1, metaData.getMaxColumnsInIndex());
        assertEquals(1, metaData.getMaxTablesInSelect());
    }

    @Test
    void answersCapabilityQuestionsWithoutAskingTheServer() throws Exception {
        for (String method : List.of("HealthCheck", "List", "Get", "Scroll")) {
            server.fail(method, Status.UNAVAILABLE);
        }

        for (Method method : DatabaseMetaData.class.getMethods()) {
            Class<?> type = method.getReturnType();
            boolean simple = type == boolean.class || type == int.class || type == long.class
                || type == String.class;

            if (simple && !Modifier.isStatic(method.getModifiers())) {
                Object[] arguments = defaultArguments(method.getParameterTypes());
                assertDoesNotThrow(() -> method.invoke(metaData, arguments), method.getName());
            }
        }
    }

    @Test
    void refusesQueriesOnceTheConnectionIsClosed() throws SQLException {
        connection.close();

        List<ThrowingSupplier<ResultSet>> queries = List.of(
            () -> metaData.getTables(null, null, "%", null),
            () -> metaData.getColumns(null, null, "%", "%"),
            () -> metaData.getPrimaryKeys(null, null, "products"),
            () -> metaData.getIndexInfo(null, null, "products", false, true),
            metaData::getSchemas,
            metaData::getCatalogs,
            metaData::getTableTypes,
            metaData::getTypeInfo,
            () -> metaData.getImportedKeys(null, null, "products"),
            () -> metaData.getProcedures(null, null, "%"));

        for (ThrowingSupplier<ResultSet> query : queries) {
            assertEquals("08003", assertThrows(SQLException.class, query::get).getSQLState());
        }
    }

    @Test
    void unwrapsToItself() throws SQLException {
        assertTrue(metaData.isWrapperFor(QdrantDatabaseMetaData.class));
        assertSame(metaData, metaData.unwrap(DatabaseMetaData.class));
        assertThrows(SQLException.class, () -> metaData.unwrap(Connection.class));
    }

    // Helpers

    private List<String> tables(String pattern, String[] types) throws SQLException {
        return column(metaData.getTables(null, null, pattern, types), "TABLE_NAME");
    }

    private static List<String> column(ResultSet rows, String label) throws SQLException {
        List<String> values = new ArrayList<>();

        try (rows) {
            while (rows.next()) {
                values.add(rows.getString(label));
            }
        }

        return values;
    }

    private static List<String> labels(ResultSet rows) throws SQLException {
        ResultSetMetaData columns = rows.getMetaData();
        List<String> labels = new ArrayList<>();

        for (int i = 1; i <= columns.getColumnCount(); i++) {
            labels.add(columns.getColumnLabel(i));
        }

        return labels;
    }

    private static void assertEmpty(ThrowingSupplier<ResultSet> query, List<String> expectedLabels)
        throws SQLException {
        ResultSet rows = assertDoesNotThrow(query);

        try (rows) {
            assertEquals(expectedLabels, labels(rows));
            assertFalse(rows.next());
        }
    }

    private static Object[] defaultArguments(Class<?>[] types) {
        Object[] arguments = new Object[types.length];

        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) {
                arguments[i] = 0;
            } else if (types[i] == boolean.class) {
                arguments[i] = false;
            } else if (types[i] == Class.class) {
                arguments[i] = Object.class;
            }
        }

        return arguments;
    }
}
