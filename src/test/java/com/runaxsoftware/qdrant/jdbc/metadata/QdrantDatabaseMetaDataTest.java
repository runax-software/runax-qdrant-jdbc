package com.runaxsoftware.qdrant.jdbc.metadata;

import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.collectionInfo;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.dense;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.point;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.unnamedVector;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.DriverInfo;
import com.runaxsoftware.qdrant.jdbc.QdrantDriver;
import com.runaxsoftware.qdrant.jdbc.schema.DriverColumns;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.Endpoint;
import com.runaxsoftware.qdrant.jdbc.support.Json;

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
        Map<String, String> productIndexes = new LinkedHashMap<>();
        productIndexes.put("price", "float");
        productIndexes.put("name", "keyword");

        server = FakeQdrantServer.start()
            .collection("products", unnamedVector(4, "Cosine", productIndexes),
                List.of(point(1, Json.object("name", "Lamp", "price", 19.5, "count", 3))))
            .collection("Mixed-Case", unnamedVector(2, "Dot", Map.of()), List.of())
            .collection("articles", collectionInfo(Json.object("dense", dense(3, "Euclid")),
                Json.object("keywords", Json.object()), Map.of()), List.of())
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

    @Test
    void readsTheCollectionsLiveOnEveryCall() throws SQLException {
        int before = server.calls(Endpoint.COLLECTIONS);
        assertEquals(4, tables("%", null).size());

        server.collection("added_later", unnamedVector(2, "Dot", Map.of()), List.of());

        assertEquals(5, tables("%", null).size());
        assertEquals(before + 2, server.calls(Endpoint.COLLECTIONS));
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
            "1 _id " + Types.VARCHAR + " VARCHAR null null NO",
            "2 count " + Types.BIGINT + " BIGINT 19 10 YES",
            "3 name " + Types.VARCHAR + " VARCHAR null null YES",
            "4 price " + Types.DOUBLE + " DOUBLE 17 10 YES",
            "5 _payload " + Types.VARCHAR + " JSON null null YES",
            "6 _vector " + Types.VARCHAR + " VECTOR 4 null YES"), described);
    }

    @Test
    void marksOnlyTheIdAsNotNull() throws SQLException {
        try (ResultSet columns = metaData.getColumns(null, null, "products", "%")) {
            while (columns.next()) {
                boolean id = columns.getString("COLUMN_NAME").equals(DriverColumns.ID.columnName());
                assertEquals(id ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullable,
                    columns.getInt("NULLABLE"));
            }
        }
    }

    @Test
    void putsDescriptionsInRemarks() throws SQLException {
        Map<String, String> remarks = new LinkedHashMap<>();

        try (ResultSet columns = metaData.getColumns(null, null, "%", "%")) {
            while (columns.next()) {
                remarks.put(columns.getString("TABLE_NAME") + "." + columns.getString("COLUMN_NAME"),
                    columns.getString("REMARKS"));
            }
        }

        assertEquals(DriverColumns.ID.remarks(), remarks.get("articles._id"));
        assertEquals(DriverColumns.PAYLOAD.remarks(), remarks.get("articles._payload"));
        assertEquals("Dense vector, 3 dimensions, Euclid distance", remarks.get("articles.dense"));
        assertEquals("Sparse vector", remarks.get("articles.keywords"));
        assertEquals("Payload field (keyword index)", remarks.get("products.name"));
        assertEquals("Payload field (not indexed)", remarks.get("products.count"));
        assertEquals("Dense vector, 4 dimensions, Cosine distance", remarks.get("products._vector"));
    }

    @Test
    void filtersColumnsByTableAndColumnPattern() throws SQLException {
        assertEquals(List.of("name"), column(metaData.getColumns(null, null, "products", "n%"),
            "COLUMN_NAME"));
        assertEquals(List.of("Mixed-Case", "articles", "products"),
            column(metaData.getColumns(null, null, "%", "\\_id"), "TABLE_NAME"));
        assertEquals(List.of(), column(metaData.getColumns("qdrant", null, "%", "%"),
            "COLUMN_NAME"));
    }

    @Test
    void readsCollectionsWhoseNamesNeedEncoding() throws SQLException {
        server.collection("my docs+v2.1", unnamedVector(2, "Dot", Map.of()), List.of());

        assertEquals(List.of("_id", "_payload", "_vector"),
            column(metaData.getColumns(null, null, "my docs+v2.1", "%"), "COLUMN_NAME"));
        assertTrue(server.requests().stream()
            .anyMatch(request -> request.path().equals("/collections/my docs+v2.1")));
    }

    @Test
    void skipsCollectionsThatDisappearWhileReading() throws SQLException {
        List<String> tables = column(metaData.getColumns(null, null, "%", "\\_payload"), "TABLE_NAME");

        assertFalse(tables.contains("ghost"));
        assertEquals(3, tables.size());
    }

    @Test
    void passesOtherErrorsOn() {
        server.fail(Endpoint.COLLECTION, 500, "disk full");

        SQLException error = assertThrows(SQLException.class,
            () -> metaData.getColumns(null, null, "products", "%"));

        assertEquals("HY000", error.getSQLState());
        assertTrue(error.getMessage().contains("disk full"), error.getMessage());
    }

    @Test
    void samplesPointsForColumns() throws SQLException {
        metaData.getColumns(null, null, "products", "%").close();

        assertEquals(1, server.scrollRequests().size());
        JsonNode scroll = server.scrollRequests().get(0);
        assertEquals(100, scroll.path("limit").asInt());
        assertTrue(scroll.path("with_payload").asBoolean());
        assertFalse(scroll.path("with_vector").asBoolean());
        assertTrue(scroll.path("offset").isMissingNode(), "starts at the first point");
    }

    @Test
    void usesOnlyPayloadIndexesWhenSamplingIsOff() throws Exception {
        try (Connection unsampled = new QdrantDriver().connect(server.jdbcUrl("sampleSize=0"),
            new Properties())) {
            assertEquals(List.of("_id", "name", "price", "_payload", "_vector"),
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
            assertEquals(DriverColumns.ID.columnName(), keys.getString("COLUMN_NAME"));
            assertEquals("_id", keys.getString(4));
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
            assertEquals("_id", indexes.getString("COLUMN_NAME"));
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
    void reportsTheCollectionsPointCountAsCardinality() throws SQLException {
        server.collection("counted", unnamedVector(2, "Dot", Map.of())
            .put("points_count", 1234), List.of());

        try (ResultSet indexes = metaData.getIndexInfo(null, null, "counted", true, true)) {
            assertTrue(indexes.next());
            assertEquals(1234, indexes.getLong("CARDINALITY"));
        }
    }

    @Test
    void skipsIndexesOnFieldsWithoutAColumn() throws SQLException {
        // A payload field named like a driver column stays in _payload, so its index has no column
        server.collection("reserved", unnamedVector(2, "Dot", Map.of("_score", "float",
            "title", "keyword")), List.of());

        assertEquals(List.of("reserved_pkey", "title_idx"),
            column(metaData.getIndexInfo(null, null, "reserved", false, true), "INDEX_NAME"));
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
            assertEquals(List.of("TABLE_SCHEM", "TABLE_CATALOG"), labels(filtered));
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
                assertEquals(name, types.getString("LOCAL_TYPE_NAME"));
            }
        }

        assertEquals(List.of("BIGINT", "DOUBLE", "VARCHAR", "JSON", "VECTOR", "MULTIVECTOR",
            "SPARSE_VECTOR", "BOOLEAN", "TIMESTAMP WITH TIME ZONE"), names);
    }

    @Test
    void givesNumbersAPrecisionAndRadix() throws SQLException {
        Map<String, String> precisions = new LinkedHashMap<>();

        try (ResultSet types = metaData.getTypeInfo()) {
            while (types.next()) {
                precisions.put(types.getString("TYPE_NAME"), types.getObject("PRECISION") + "/"
                    + types.getObject("NUM_PREC_RADIX"));
            }
        }

        assertEquals("19/10", precisions.get("BIGINT"));
        assertEquals("17/10", precisions.get("DOUBLE"));
        assertEquals("null/null", precisions.get("VARCHAR"));
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

    @Test
    void answersEmptyResultsWithoutAskingTheServer() throws SQLException {
        int requests = server.requests().size();

        metaData.getImportedKeys(null, null, "products").close();
        metaData.getProcedures(null, null, "%").close();
        metaData.getSchemas().close();
        metaData.getCatalogs().close();
        metaData.getTableTypes().close();
        metaData.getTypeInfo().close();

        assertEquals(requests, server.requests().size());
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
        for (Endpoint endpoint : Endpoint.values()) {
            server.fail(endpoint, 503, "unavailable");
        }

        int requests = server.requests().size();

        for (Method method : DatabaseMetaData.class.getMethods()) {
            Class<?> type = method.getReturnType();
            boolean simple = type == boolean.class || type == int.class || type == long.class
                || type == String.class;

            if (simple && !Modifier.isStatic(method.getModifiers())) {
                Object[] arguments = defaultArguments(method.getParameterTypes());
                assertDoesNotThrow(() -> method.invoke(metaData, arguments), method.getName());
            }
        }

        assertEquals(requests, server.requests().size());
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
            () -> metaData.getSchemas(null, "%"),
            metaData::getCatalogs,
            metaData::getTableTypes,
            metaData::getTypeInfo,
            () -> metaData.getImportedKeys(null, null, "products"),
            () -> metaData.getProcedures(null, null, "%"),
            metaData::getClientInfoProperties);

        for (ThrowingSupplier<ResultSet> query : queries) {
            assertEquals("08003", assertThrows(SQLException.class, query::get).getSQLState());
        }
    }

    @Test
    void unwrapsToItself() throws SQLException {
        assertTrue(metaData.isWrapperFor(QdrantDatabaseMetaData.class));
        assertTrue(metaData.isWrapperFor(DatabaseMetaData.class));
        assertFalse(metaData.isWrapperFor(Connection.class));
        assertSame(metaData, metaData.unwrap(DatabaseMetaData.class));
        assertSame(metaData, metaData.unwrap(QdrantDatabaseMetaData.class));
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
