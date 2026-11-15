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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * Every system table, in the order {@code getTables} lists them, which is by name.
     */
    private static final List<String> SYSTEM_TABLES = List.of("aliases", "cluster",
        "cluster_telemetry", "collections",
        "health", "issues", "metrics", "optimizations", "peers", "quotas", "shard_keys",
        "shard_snapshots", "shards", "snapshots", "telemetry");

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
        assertEquals(List.of("Mixed-Case", "articles", "ghost", "products"), tables(null, null));
    }

    @Test
    void reportsTablesWithJdbcColumns() throws SQLException {
        try (ResultSet tables = metaData.getTables(null, null, "products", null)) {
            assertEquals(List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS",
                "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SELF_REFERENCING_COL_NAME", "REF_GENERATION"),
                labels(tables));
            assertTrue(tables.next());
            assertEquals("qdrant", tables.getString("TABLE_CAT"));
            assertEquals("collections", tables.getString("TABLE_SCHEM"));
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
        assertEquals(SYSTEM_TABLES, systemTables("%", new String[] {"VIEW", "SYSTEM TABLE"}));
    }

    @Test
    void matchesFiltersForItsCatalogAndSchema() throws SQLException {
        // 4 collections, and the system tables wherever the schema filter takes them in too
        int all = 4 + SYSTEM_TABLES.size();

        assertEquals(all, column(metaData.getTables("", "", "%", null), "TABLE_NAME").size());
        assertEquals(all, column(metaData.getTables(null, "%", "%", null), "TABLE_NAME").size());
        assertEquals(4, column(metaData.getTables("qdrant", "collections", "%", null),
            "TABLE_NAME").size());
        assertEquals(4, column(metaData.getTables(null, "coll%", "%", null), "TABLE_NAME").size());
        assertEquals(SYSTEM_TABLES.size(), column(metaData.getTables("qdrant", "system", "%", null),
            "TABLE_NAME").size());
        assertEquals(List.of(), column(metaData.getTables("other", null, "%", null), "TABLE_NAME"));
        assertEquals(List.of(), column(metaData.getTables("QDRANT", null, "%", null), "TABLE_NAME"));
        assertEquals(List.of(), column(metaData.getTables(null, "public", "%", null), "TABLE_NAME"));
    }

    @Test
    void putsEveryRowInTheOneCatalogAndSchema() throws SQLException {
        List<ResultSet> results = List.of(
            metaData.getTables(null, "collections", "%", null),
            metaData.getColumns(null, "collections", "%", "%"),
            metaData.getPrimaryKeys(null, null, "products"),
            metaData.getIndexInfo(null, null, "products", false, true));

        for (ResultSet rows : results) {
            try (rows) {
                assertTrue(rows.next());

                do {
                    assertEquals("qdrant", rows.getString("TABLE_CAT"));
                    assertEquals("collections", rows.getString("TABLE_SCHEM"));
                } while (rows.next());
            }
        }
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
        assertEquals(List.of(), column(metaData.getColumns("other", null, "%", "%"),
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
        assertEquals(List.of(), column(metaData.getPrimaryKeys("other", null, "products"),
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

    // Keeping schemas between calls

    @Test
    void readsEachCollectionOnceForEachRefresh() throws SQLException {
        server.alias("current", "products");

        int listings = server.calls(Endpoint.COLLECTIONS);

        // The order DataGrip introspects in: list the tables, then ask about each one
        for (int refresh = 1; refresh <= 2; refresh++) {
            for (String table : tables("%", null)) {
                metaData.getColumns(null, null, table, "%").close();
                metaData.getPrimaryKeys(null, null, table).close();
                metaData.getIndexInfo(null, null, table, false, true).close();
            }

            assertEquals(listings + refresh, server.calls(Endpoint.COLLECTIONS));
            assertEquals(refresh, server.calls(Endpoint.ALIASES));
            assertEquals(refresh, reads("products"));
            assertEquals(refresh, reads("articles"));
            assertEquals(0, reads("current"), "a view is read with its collection");
            assertEquals(2 * refresh, reads("ghost"), "a missing collection isn't kept");
        }
    }

    @Test
    void showsNewFieldsAfterARefresh() throws SQLException {
        assertFalse(columns("products").contains("color"));
        server.collection("products", unnamedVector(4, "Cosine", Map.of()),
            List.of(point(1, Json.object("name", "Lamp", "color", "red"))));

        assertFalse(columns("products").contains("color"), "kept until a refresh");
        tables("%", null);

        assertTrue(columns("products").contains("color"));
    }

    @Test
    void refreshesOnlyTheTablesItLists() throws SQLException {
        metaData.getColumns(null, null, "%", "%").close();

        tables("prod%", null);
        metaData.getColumns(null, null, "%", "%").close();

        assertEquals(2, reads("products"));
        assertEquals(1, reads("articles"));
    }

    @Test
    void dropsADeletedCollectionOnRefresh() throws SQLException {
        assertEquals(3, indexes("products").size());
        server.deleteCollection("products");

        assertEquals(3, indexes("products").size(), "kept until a refresh");
        tables("%", null);

        assertEquals(List.of(), indexes("products"));
        assertEquals(List.of(), columns("products"));
        assertEquals(List.of(), column(metaData.getPrimaryKeys(null, null, "products"),
            "PK_NAME"));
    }

    @Test
    void listsCollectionsAndAliasesOnlyWhenNoListIsKept() throws SQLException {
        int listings = server.calls(Endpoint.COLLECTIONS);

        metaData.getPrimaryKeys(null, null, "products").close();
        metaData.getColumns(null, null, "%", "%").close();
        metaData.getIndexInfo(null, null, "products", false, true).close();

        assertEquals(listings + 1, server.calls(Endpoint.COLLECTIONS));
        assertEquals(1, server.calls(Endpoint.ALIASES));
    }

    @Test
    void keepsTheListsForEveryMetadataObjectOfTheConnection() throws SQLException {
        int listings = server.calls(Endpoint.COLLECTIONS);
        tables("%", null);

        DatabaseMetaData other = connection.getMetaData();
        other.getColumns(null, null, "products", "%").close();
        other.getPrimaryKeys(null, null, "products").close();
        connection.getMetaData().getIndexInfo(null, null, "products", false, true).close();

        assertEquals(listings + 1, server.calls(Endpoint.COLLECTIONS));
        assertEquals(1, server.calls(Endpoint.ALIASES));
    }

    @Test
    void findsACollectionAndAnAliasCreatedSinceTheLastListing() throws SQLException {
        tables("%", null);
        int collections = server.calls(Endpoint.COLLECTIONS);
        int aliases = server.calls(Endpoint.ALIASES);

        server.collection("added", unnamedVector(2, "Dot", Map.of()), List.of());
        assertEquals(List.of("_id", "_payload", "_vector"), columns("added"));
        assertEquals(List.of("added_pkey"), column(metaData.getPrimaryKeys(null, null, "added"),
            "PK_NAME"));
        assertEquals(List.of("added_pkey"), indexes("added"));
        assertEquals(collections + 1, server.calls(Endpoint.COLLECTIONS), "listed again once");
        assertEquals(aliases + 1, server.calls(Endpoint.ALIASES));

        server.alias("current", "products");
        assertEquals(columns("products"), columns("current"));
        assertEquals(List.of(), column(metaData.getPrimaryKeys(null, null, "current"),
            "PK_NAME"));
        assertEquals(List.of(), indexes("current"));
        assertEquals(collections + 2, server.calls(Endpoint.COLLECTIONS), "listed again once");
        assertEquals(aliases + 2, server.calls(Endpoint.ALIASES));
    }

    @Test
    void findsANewCollectionWhicheverCallAsksFirst() throws SQLException {
        tables("%", null);
        int collections = server.calls(Endpoint.COLLECTIONS);

        server.collection("keyed", unnamedVector(2, "Dot", Map.of()), List.of());
        assertEquals(List.of("keyed_pkey"), column(metaData.getPrimaryKeys(null, null, "keyed"),
            "PK_NAME"));

        server.collection("indexed", unnamedVector(2, "Dot", Map.of()), List.of());
        assertEquals(List.of("indexed_pkey"), indexes("indexed"));

        server.collection("new_one", unnamedVector(2, "Dot", Map.of()), List.of());
        assertEquals(List.of("new_one"), column(metaData.getColumns(null, null, "new\\_one",
            "\\_id"), "TABLE_NAME"), "an escaped _ is no wildcard");

        assertEquals(collections + 3, server.calls(Endpoint.COLLECTIONS), "once for each");
    }

    @Test
    void listsAgainOnceForEachAskAboutAMissingName() throws SQLException {
        tables("%", null);
        int collections = server.calls(Endpoint.COLLECTIONS);
        int aliases = server.calls(Endpoint.ALIASES);

        assertEquals(List.of(), columns("nothing"));
        assertEquals(List.of(), column(metaData.getPrimaryKeys(null, null, "nothing"),
            "PK_NAME"));
        assertEquals(List.of(), indexes("nothing"));

        assertEquals(collections + 3, server.calls(Endpoint.COLLECTIONS));
        assertEquals(aliases + 3, server.calls(Endpoint.ALIASES));
        assertEquals(0, reads("nothing"));
    }

    @Test
    void usesTheKeptListsForPatternsWithWildcards() throws SQLException {
        tables("%", null);
        int collections = server.calls(Endpoint.COLLECTIONS);
        server.collection("added", unnamedVector(2, "Dot", Map.of()), List.of())
            .alias("current", "products");

        assertEquals(List.of(), columns("add%"));
        assertEquals(List.of(), columns("adde_"));
        assertEquals(List.of(), columns("curren_"));
        assertEquals(List.of("Mixed-Case", "articles", "products"), column(metaData.getColumns(
            null, null, "%", "\\_id"), "TABLE_NAME"), "as listed, without ghost");

        assertEquals(collections, server.calls(Endpoint.COLLECTIONS));
        assertEquals(1, server.calls(Endpoint.ALIASES), "only the listing's");
    }

    @Test
    void costsNothingForNamesTheKeptListsHave() throws SQLException {
        server.alias("current", "products");
        tables("%", null);
        metaData.getColumns(null, null, "%", "%").close();
        int requests = server.requests().size();

        for (String table : List.of("products", "current", "ghost", "Mixed-Case")) {
            metaData.getColumns(null, null, table, "%").close();
            metaData.getPrimaryKeys(null, null, table).close();
        }

        metaData.getIndexInfo(null, null, "products", false, true).close();
        metaData.getIndexInfo(null, null, "current", false, true).close();

        assertEquals(requests + 1, server.requests().size(), "only ghost's failed read again");
    }

    @Test
    void keepsTheListsOfTheKindsItListedOnly() throws SQLException {
        server.alias("current", "products");
        tables("%", new String[] {"TABLE"});
        int listings = server.calls(Endpoint.COLLECTIONS);

        assertEquals(columns("products"), columns("current"));

        assertEquals(listings, server.calls(Endpoint.COLLECTIONS), "kept by getTables");
        assertEquals(1, server.calls(Endpoint.ALIASES), "listed when first needed");
    }

    @Test
    void listsEveryTimeWithTheCacheOff() throws Exception {
        try (Connection uncached = new QdrantDriver().connect(server.jdbcUrl("schemaCache=0"),
            new Properties())) {
            DatabaseMetaData uncachedMetaData = uncached.getMetaData();
            int listings = server.calls(Endpoint.COLLECTIONS);

            uncachedMetaData.getTables(null, null, "%", null).close();
            uncachedMetaData.getColumns(null, null, "products", "%").close();
            uncachedMetaData.getPrimaryKeys(null, null, "products").close();
            uncachedMetaData.getIndexInfo(null, null, "products", false, true).close();

            assertEquals(listings + 4, server.calls(Endpoint.COLLECTIONS));
            assertEquals(1, server.calls(Endpoint.ALIASES), "a table needs no alias list");

            uncachedMetaData.getColumns(null, null, "nothing", "%").close();
            assertEquals(listings + 5, server.calls(Endpoint.COLLECTIONS), "no second listing");
            assertEquals(2, server.calls(Endpoint.ALIASES));
        }
    }

    @Test
    void keepsSchemasForTheWholeConnection() throws SQLException {
        metaData.getColumns(null, null, "products", "%").close();
        connection.getMetaData().getIndexInfo(null, null, "products", false, true).close();

        assertEquals(1, reads("products"));
    }

    @Test
    void readsEveryTimeWithTheCacheOff() throws Exception {
        try (Connection uncached = new QdrantDriver().connect(server.jdbcUrl("schemaCache=0"),
            new Properties())) {
            DatabaseMetaData uncachedMetaData = uncached.getMetaData();

            uncachedMetaData.getColumns(null, null, "products", "%").close();
            uncachedMetaData.getIndexInfo(null, null, "products", false, true).close();
        }

        assertEquals(2, reads("products"));
    }

    // Views

    @Test
    void listsSystemTablesThenCollectionsThenViews() throws SQLException {
        server.alias("current", "products").alias("best", "articles");
        List<String> listed = new ArrayList<>();

        try (ResultSet tables = metaData.getTables(null, null, "%", null)) {
            while (tables.next()) {
                listed.add(tables.getString("TABLE_CAT") + "." + tables.getString("TABLE_SCHEM")
                    + "." + tables.getString("TABLE_NAME") + " " + tables.getString("TABLE_TYPE")
                    + " " + tables.getString("REMARKS"));
            }
        }

        assertEquals(List.of(
            "qdrant.system.aliases SYSTEM TABLE Every alias, with the collection it points at",
            "qdrant.system.cluster SYSTEM TABLE The cluster as the connected peer sees it, in "
                + "one row",
            "qdrant.system.cluster_telemetry SYSTEM TABLE The cluster's telemetry, gathered from "
                + "every peer, as one row per value in it",
            "qdrant.system.collections SYSTEM TABLE Every collection on the server, with its "
                + "state and parameters",
            "qdrant.system.health SYSTEM TABLE Qdrant's health probes, as one row per probe",
            "qdrant.system.issues SYSTEM TABLE The problems and suggestions Qdrant reports "
                + "about itself",
            "qdrant.system.metrics SYSTEM TABLE Qdrant's Prometheus metrics, as one row per "
                + "sample",
            "qdrant.system.optimizations SYSTEM TABLE Every collection's running, queued, and "
                + "latest completed optimizations",
            "qdrant.system.peers SYSTEM TABLE Every peer of the cluster, with its address",
            "qdrant.system.quotas SYSTEM TABLE The server's limits on memory and disk, and how "
                + "much is used, in one row",
            "qdrant.system.shard_keys SYSTEM TABLE Every collection's shard keys, for custom "
                + "sharding",
            "qdrant.system.shard_snapshots SYSTEM TABLE Every snapshot of a single shard, on "
                + "the connected peer",
            "qdrant.system.shards SYSTEM TABLE Every shard of every collection, and the peer "
                + "holding each copy",
            "qdrant.system.snapshots SYSTEM TABLE Every snapshot, of a collection or of the "
                + "whole storage",
            "qdrant.system.telemetry SYSTEM TABLE Qdrant's telemetry, as one row per value in it",
            "qdrant.collections.Mixed-Case TABLE null",
            "qdrant.collections.articles TABLE null",
            "qdrant.collections.ghost TABLE null",
            "qdrant.collections.products TABLE null",
            "qdrant.collections.best VIEW Alias of articles",
            "qdrant.collections.current VIEW Alias of products"), listed);
    }

    @Test
    void filtersViewsByPatternAndType() throws SQLException {
        server.alias("current", "products").alias("Current-2", "articles");

        assertEquals(List.of("Current-2", "current"), tables("%", new String[] {"view"}));
        assertEquals(List.of("current"), tables("cur%", null));
        assertEquals(List.of("Current-2"), tables("Current\\-_", new String[] {"VIEW", "TABLE"}));
        assertEquals(4, tables("%", new String[] {"TABLE"}).size());
        assertEquals(List.of(), tables("%", new String[] {"SYSTEM TABLE"}));
        assertEquals(SYSTEM_TABLES, systemTables("%", new String[] {"SYSTEM TABLE"}));
        assertEquals(List.of(), column(metaData.getTables("other", null, "%", null),
            "TABLE_NAME"));
    }

    @Test
    void asksOnlyForTheKindsTheTypeFilterTakesIn() throws SQLException {
        int collections = server.calls(Endpoint.COLLECTIONS);

        metaData.getTables(null, null, "%", new String[] {"TABLE"}).close();
        assertEquals(collections + 1, server.calls(Endpoint.COLLECTIONS));
        assertEquals(0, server.calls(Endpoint.ALIASES));

        metaData.getTables(null, null, "%", new String[] {"VIEW"}).close();
        assertEquals(collections + 1, server.calls(Endpoint.COLLECTIONS));
        assertEquals(1, server.calls(Endpoint.ALIASES));
    }

    @Test
    void listsTablesAndViewsWithOneCallEach() throws SQLException {
        server.alias("current", "products");
        int requests = server.requests().size();

        tables("%", null);

        assertEquals(requests + 2, server.requests().size());
        assertEquals(0, server.calls(Endpoint.COLLECTION), "a listing reads no collection");
    }

    @Test
    void givesAViewItsCollectionsColumns() throws SQLException {
        server.alias("current", "products");

        List<String> view = describe(metaData.getColumns(null, null, "current", "%"));

        assertEquals(describe(metaData.getColumns(null, null, "products", "%")).stream()
            .map(row -> row.replace("products.", "current.")).toList(), view);
        assertEquals("current._id 1 VARCHAR NO " + DriverColumns.ID.remarks(), view.get(0));
    }

    @Test
    void listsViewColumnsInTableNameOrder() throws SQLException {
        server.alias("current", "products");

        assertEquals(List.of("Mixed-Case", "articles", "current", "products"),
            column(metaData.getColumns(null, null, "%", "\\_id"), "TABLE_NAME"));
    }

    @Test
    void readsAViewsColumnsOnceWithItsCollection() throws SQLException {
        server.alias("current", "products");

        columns("products");
        columns("current");
        indexes("products");

        assertEquals(1, reads("products"));
        assertEquals(0, reads("current"));
    }

    @Test
    void reportsNoKeyOrIndexesForViews() throws SQLException {
        server.alias("current", "products");

        assertEquals(List.of(), column(metaData.getPrimaryKeys(null, null, "current"), "PK_NAME"));
        assertEquals(List.of(), indexes("current"));
        assertEquals(List.of(), column(metaData.getIndexInfo(null, null, "current", true, true),
            "INDEX_NAME"));
    }

    @Test
    void showsTheNewCollectionAfterAnAliasMovesAndARefresh() throws SQLException {
        server.alias("current", "products");
        assertEquals(columns("products"), columns("current"));

        server.alias("current", "articles");
        assertEquals(columns("products"), columns("current"), "kept until a refresh");

        assertEquals(List.of("Alias of articles"),
            column(metaData.getTables(null, null, "current", null), "REMARKS"));
        assertEquals(columns("articles"), columns("current"));
    }

    @Test
    void refreshingAViewReadsItsCollectionAgain() throws SQLException {
        server.alias("current", "products");
        assertFalse(columns("current").contains("color"));
        server.collection("products", unnamedVector(4, "Cosine", Map.of()),
            List.of(point(1, Json.object("name", "Lamp", "color", "red"))));

        assertFalse(columns("current").contains("color"), "kept until a refresh");
        assertEquals(List.of("current"), tables("current", null));

        assertTrue(columns("current").contains("color"));
        assertTrue(columns("products").contains("color"));
    }

    @Test
    void dropsADeletedAliasOnRefresh() throws SQLException {
        server.alias("current", "products");
        assertEquals(columns("products"), columns("current"));

        server.deleteAlias("current");
        assertEquals(columns("products"), columns("current"), "kept until a refresh");

        assertEquals(List.of(), tables("current", null));
        assertEquals(List.of(), columns("current"));
        assertEquals(3, indexes("products").size(), "the collection stays");
    }

    @Test
    void skipsViewsWhoseCollectionIsGone() throws SQLException {
        server.alias("stale", "ghost").alias("dangling", "deleted");

        assertEquals(List.of("dangling", "stale"), tables("%", new String[] {"VIEW"}));
        assertEquals(List.of(), columns("stale"));
        assertEquals(List.of(), columns("dangling"));
    }

    // System tables

    @Test
    void listsTheSystemTablesWithoutCallingQdrant() throws SQLException {
        int collections = server.calls(Endpoint.COLLECTIONS);

        assertEquals(SYSTEM_TABLES, systemTables("%", null));
        assertEquals(List.of("collections"), systemTables("coll%", null));
        assertEquals(List.of("shard_keys", "shard_snapshots", "shards"),
            systemTables("shard%", null));
        assertEquals(List.of(), systemTables("products", null));
        assertEquals(List.of(), systemTables("%", new String[] {"TABLE", "VIEW"}));

        assertEquals(collections, server.calls(Endpoint.COLLECTIONS));
        assertEquals(0, server.calls(Endpoint.ALIASES));
    }

    @Test
    void putsTheSystemTablesInTheirOwnSchema() throws SQLException {
        try (ResultSet tables = metaData.getTables(null, "system", "aliases", null)) {
            assertTrue(tables.next());
            assertEquals("qdrant", tables.getString("TABLE_CAT"));
            assertEquals("system", tables.getString("TABLE_SCHEM"));
            assertEquals("aliases", tables.getString("TABLE_NAME"));
            assertEquals("SYSTEM TABLE", tables.getString("TABLE_TYPE"));
            assertEquals("Every alias, with the collection it points at",
                tables.getString("REMARKS"));
            assertFalse(tables.next());
        }
    }

    @Test
    void describesEachColumnOfASystemTable() throws SQLException {
        assertEquals(List.of(
            "aliases.alias_name 1 VARCHAR YES The alias name",
            "aliases.collection_name 2 VARCHAR YES The collection the alias points at"),
            describe(metaData.getColumns(null, "system", "aliases", "%")));

        try (ResultSet columns = metaData.getColumns(null, "system", "collections",
            "points_count")) {
            assertTrue(columns.next());
            assertEquals("qdrant", columns.getString("TABLE_CAT"));
            assertEquals("system", columns.getString("TABLE_SCHEM"));
            assertEquals(Types.BIGINT, columns.getInt("DATA_TYPE"));
            assertEquals("BIGINT", columns.getString("TYPE_NAME"));
            assertEquals(19, columns.getInt("COLUMN_SIZE"));
            assertEquals(10, columns.getInt("NUM_PREC_RADIX"));
            assertEquals(4, columns.getInt("ORDINAL_POSITION"));
            assertFalse(columns.next());
        }
    }

    /**
     * A collection may be named like a system table: the two sit in different schemas, and JDBC
     * orders columns by schema, so the collection's come first.
     */
    @Test
    void describesACollectionsColumnsBeforeTheSystemTableOfTheSameName() throws SQLException {
        server.collection("aliases", unnamedVector(2, "Dot", Map.of()), List.of());
        List<String> described = new ArrayList<>();

        try (ResultSet columns = metaData.getColumns(null, null, "aliases", "%")) {
            while (columns.next()) {
                described.add(columns.getString("TABLE_SCHEM") + "."
                    + columns.getString("TABLE_NAME") + "." + columns.getString("COLUMN_NAME"));
            }
        }

        assertEquals(List.of(
            "collections.aliases._id",
            "collections.aliases._payload",
            "collections.aliases._vector",
            "system.aliases.alias_name",
            "system.aliases.collection_name"), described);
    }

    @Test
    void givesTheSystemTablesNoKeyAndNoIndexes() throws SQLException {
        assertFalse(metaData.getPrimaryKeys("qdrant", "system", "collections").next());
        assertFalse(metaData.getIndexInfo("qdrant", "system", "collections", false, true).next());
        assertFalse(metaData.getPrimaryKeys(null, "system", "aliases").next());
        assertFalse(metaData.getIndexInfo(null, "system", "aliases", false, true).next());
    }

    // Schemas, catalogs, types

    @Test
    void hasOneCatalogAndTwoSchemas() throws SQLException {
        try (ResultSet schemas = metaData.getSchemas();
             ResultSet catalogs = metaData.getCatalogs()) {
            assertEquals(List.of("TABLE_SCHEM", "TABLE_CATALOG"), labels(schemas));
            assertEquals(List.of("TABLE_CAT"), labels(catalogs));

            assertTrue(schemas.next());
            assertEquals("collections", schemas.getString("TABLE_SCHEM"));
            assertEquals("qdrant", schemas.getString("TABLE_CATALOG"));
            assertTrue(schemas.next());
            assertEquals("system", schemas.getString("TABLE_SCHEM"));
            assertEquals("qdrant", schemas.getString("TABLE_CATALOG"));
            assertFalse(schemas.next());

            assertTrue(catalogs.next());
            assertEquals("qdrant", catalogs.getString("TABLE_CAT"));
            assertFalse(catalogs.next());
        }
    }

    @Test
    void filtersSchemasByCatalogAndPattern() throws SQLException {
        assertEquals(List.of("collections", "system"),
            column(metaData.getSchemas(null, "%"), "TABLE_SCHEM"));
        assertEquals(List.of("collections"), column(metaData.getSchemas("qdrant", "coll%"),
            "TABLE_SCHEM"));
        assertEquals(List.of("system"), column(metaData.getSchemas("qdrant", "system"),
            "TABLE_SCHEM"));
        assertEquals(List.of("collections", "system"),
            column(metaData.getSchemas("", ""), "TABLE_SCHEM"));
        assertEquals(List.of(), column(metaData.getSchemas("other", null), "TABLE_SCHEM"));
        assertEquals(List.of(), column(metaData.getSchemas(null, "public"), "TABLE_SCHEM"));
    }

    @Test
    void hasSystemTablesTablesAndViews() throws SQLException {
        assertEquals(List.of("SYSTEM TABLE", "TABLE", "VIEW"),
            column(metaData.getTableTypes(), "TABLE_TYPE"));
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
    void listsTheProceduresACallRunsByName() throws SQLException {
        List<String> names = new ArrayList<>();

        try (ResultSet procedures = metaData.getProcedures(null, null, "%")) {
            while (procedures.next()) {
                assertEquals("qdrant", procedures.getString("PROCEDURE_CAT"));
                assertEquals("system", procedures.getString("PROCEDURE_SCHEM"));
                assertEquals(DatabaseMetaData.procedureNoResult,
                    procedures.getShort("PROCEDURE_TYPE"));
                assertNotNull(procedures.getString("REMARKS"));
                names.add(procedures.getString("PROCEDURE_NAME"));
            }
        }

        assertEquals(List.of("abort_resharding", "abort_shard_transfer", "create_full_snapshot",
            "create_shard_key", "create_shard_snapshot", "create_snapshot",
            "delete_full_snapshot", "delete_shard_key", "delete_shard_snapshot",
            "delete_snapshot", "download_full_snapshot", "download_shard_snapshot",
            "download_snapshot", "drop_replica", "move_shard", "recover_raft",
            "recover_shard_snapshot", "recover_snapshot", "remove_peer", "replicate_points",
            "replicate_shard", "restart_shard_transfer", "start_resharding",
            "stream_shard_snapshot", "upload_shard_snapshot", "upload_snapshot"),
            names);
        assertTrue(metaData.supportsStoredProcedures());
    }

    @Test
    void describesAProceduresArgumentsInTheOrderACallGivesThem() throws SQLException {
        List<String> arguments = new ArrayList<>();

        try (ResultSet columns = metaData.getProcedureColumns("qdrant", "system",
            "create_shard_key", "%")) {
            while (columns.next()) {
                assertEquals(DatabaseMetaData.procedureColumnIn, columns.getShort("COLUMN_TYPE"));
                arguments.add(columns.getInt("ORDINAL_POSITION") + " "
                    + columns.getString("COLUMN_NAME") + " " + columns.getString("TYPE_NAME")
                    + " " + columns.getString("IS_NULLABLE"));
            }
        }

        assertEquals(List.of("1 collection VARCHAR NO", "2 shard_key VARCHAR NO",
            "3 shards_number BIGINT YES", "4 replication_factor BIGINT YES",
            "5 placement JSON YES"), arguments);
    }

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
        assertEmpty(() -> metaData.getProcedures(null, "collections", "%"), List.of("PROCEDURE_CAT",
            "PROCEDURE_SCHEM", "PROCEDURE_NAME", "RESERVED1", "RESERVED2", "RESERVED3", "REMARKS",
            "PROCEDURE_TYPE", "SPECIFIC_NAME"));
        assertEmpty(() -> metaData.getProcedureColumns(null, null, "nope%", "%"), List.of(
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
        assertFalse(metaData.isReadOnly());
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

    // The collections' own schema, so the system tables don't come into what these assert
    private List<String> tables(String pattern, String[] types) throws SQLException {
        return column(metaData.getTables(null, "collections", pattern, types), "TABLE_NAME");
    }

    private List<String> systemTables(String pattern, String[] types) throws SQLException {
        return column(metaData.getTables(null, "system", pattern, types), "TABLE_NAME");
    }

    private List<String> columns(String table) throws SQLException {
        return column(metaData.getColumns(null, "collections", table, "%"), "COLUMN_NAME");
    }

    private List<String> indexes(String table) throws SQLException {
        return column(metaData.getIndexInfo(null, null, table, false, true), "INDEX_NAME");
    }

    /**
     * Each column as "table.column position type nullable remarks".
     */
    private static List<String> describe(ResultSet columns) throws SQLException {
        List<String> described = new ArrayList<>();

        try (columns) {
            while (columns.next()) {
                described.add(columns.getString("TABLE_NAME") + "."
                    + columns.getString("COLUMN_NAME") + " "
                    + columns.getInt("ORDINAL_POSITION") + " "
                    + columns.getString("TYPE_NAME") + " "
                    + columns.getString("IS_NULLABLE") + " "
                    + columns.getString("REMARKS"));
            }
        }

        return described;
    }

    /**
     * How many times the driver asked Qdrant for a collection's configuration.
     */
    private long reads(String collection) {
        return server.requests().stream()
            .filter(request -> request.method().equals("GET")
                && request.path().equals("/collections/" + collection))
            .count();
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
