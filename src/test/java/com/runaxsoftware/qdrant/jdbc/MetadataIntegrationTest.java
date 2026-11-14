package com.runaxsoftware.qdrant.jdbc;

import static com.runaxsoftware.qdrant.jdbc.support.Json.array;
import static com.runaxsoftware.qdrant.jdbc.support.Json.object;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the sample collections back through {@link DatabaseMetaData}, the way DataGrip's
 * database explorer does. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class MetadataIntegrationTest {

    private static final String PREFIX =
        "jdbc_it_" + UUID.randomUUID().toString().substring(0, 8) + "_";

    private static QdrantGateway gateway;
    private static Connection connection;
    private static DatabaseMetaData metaData;

    @BeforeAll
    static void createCollections() throws SQLException {
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, PREFIX);
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
        metaData = connection.getMetaData();
    }

    @AfterAll
    static void deleteCollections() throws SQLException {
        if (connection != null) {
            connection.close();
        }

        if (gateway != null) {
            SampleCollections.deleteAliases(gateway, PREFIX);
            SampleCollections.delete(gateway, PREFIX);
            gateway.close();
        }
    }

    @Test
    void listsCollectionsAsTables() throws SQLException {
        List<String> tables = new ArrayList<>();

        try (ResultSet rows = metaData.getTables(null, null, like(PREFIX) + "%", null)) {
            while (rows.next()) {
                tables.add(rows.getString("TABLE_NAME"));
                assertEquals("TABLE", rows.getString("TABLE_TYPE"));
                assertEquals("qdrant", rows.getString("TABLE_CAT"));
                assertEquals("collections", rows.getString("TABLE_SCHEM"));
            }
        }

        assertEquals(List.of(PREFIX + "articles", PREFIX + "events", PREFIX + "products"), tables);
    }

    @Test
    void listsNoCollectionsForOtherTableTypes() throws SQLException {
        try (ResultSet rows = metaData.getTables(null, "collections", "%",
            new String[] {"SYSTEM TABLE"})) {
            assertFalse(rows.next());
        }
    }

    @Test
    void listsTheSystemTablesInTheSystemSchema() throws SQLException {
        List<String> listed = new ArrayList<>();

        try (ResultSet rows = metaData.getTables(null, "system", "%", null)) {
            while (rows.next()) {
                listed.add(rows.getString("TABLE_SCHEM") + "." + rows.getString("TABLE_NAME")
                    + " " + rows.getString("TABLE_TYPE"));
            }
        }

        assertEquals(List.of("system.aliases SYSTEM TABLE", "system.cluster SYSTEM TABLE",
            "system.collections SYSTEM TABLE", "system.health SYSTEM TABLE",
            "system.issues SYSTEM TABLE",
            "system.metrics SYSTEM TABLE", "system.peers SYSTEM TABLE",
            "system.shard_keys SYSTEM TABLE", "system.shards SYSTEM TABLE",
            "system.snapshots SYSTEM TABLE", "system.telemetry SYSTEM TABLE"), listed);
    }

    @Test
    void listsAliasesAsViewsAfterTheTables() throws SQLException {
        String view = PREFIX + "current_products";
        SampleCollections.alias(gateway, view, PREFIX + "products");

        try {
            List<String> listed = new ArrayList<>();

            try (ResultSet rows = metaData.getTables(null, null, like(PREFIX) + "%", null)) {
                while (rows.next()) {
                    listed.add(rows.getString("TABLE_CAT") + "." + rows.getString("TABLE_SCHEM")
                        + "." + rows.getString("TABLE_NAME") + " " + rows.getString("TABLE_TYPE")
                        + " " + rows.getString("REMARKS"));
                }
            }

            String prefix = "qdrant.collections." + PREFIX;
            assertEquals(List.of(prefix + "articles TABLE null", prefix + "events TABLE null",
                prefix + "products TABLE null",
                prefix + "current_products VIEW Alias of " + PREFIX + "products"), listed);
            assertEquals(List.of(view), tables(like(PREFIX) + "%", "VIEW"));
        } finally {
            SampleCollections.deleteAliases(gateway, PREFIX);
        }

        assertEquals(List.of(), tables(like(view), "VIEW"));
    }

    @Test
    void readsACollectionAndItsPointsByAnAlias() throws SQLException {
        // What the driver relies on: Qdrant resolves an alias wherever a collection name goes
        String view = PREFIX + "by_alias";
        SampleCollections.alias(gateway, view, PREFIX + "products");

        try {
            assertEquals(5, gateway.collection(view).pointsCount());
            assertEquals(5, gateway.scroll(new ScrollRequest(view, 10, null, true, false))
                .points().size());
        } finally {
            SampleCollections.deleteAliases(gateway, PREFIX);
        }
    }

    @Test
    void givesAViewItsCollectionsColumnsButNoKeyOrIndexes() throws SQLException {
        String view = PREFIX + "current_products";
        SampleCollections.alias(gateway, view, PREFIX + "products");

        try {
            assertEquals(columns("products"), columns("current_products"));

            try (ResultSet keys = metaData.getPrimaryKeys(null, null, view);
                 ResultSet indexes = metaData.getIndexInfo(null, null, view, false, true)) {
                assertFalse(keys.next());
                assertFalse(indexes.next());
            }
        } finally {
            SampleCollections.deleteAliases(gateway, PREFIX);
        }
    }

    @Test
    void showsTheNewCollectionAfterAnAliasMoves() throws SQLException {
        String view = PREFIX + "current";
        SampleCollections.alias(gateway, view, PREFIX + "products");

        try {
            assertEquals(columns("products"), columns("current"));

            SampleCollections.alias(gateway, view, PREFIX + "articles");
            assertEquals(columns("products"), columns("current"), "kept until a refresh");
            assertEquals(List.of(view), tables(like(view), "VIEW"));

            assertEquals(columns("articles"), columns("current"));

            try (ResultSet rows = metaData.getTables(null, null, like(view), null)) {
                assertTrue(rows.next());
                assertEquals("Alias of " + PREFIX + "articles", rows.getString("REMARKS"));
            }
        } finally {
            SampleCollections.deleteAliases(gateway, PREFIX);
        }
    }

    @Test
    void selectsFromAnAlias() throws SQLException {
        String view = PREFIX + "current";
        SampleCollections.alias(gateway, view, PREFIX + "products");

        try (Statement statement = connection.createStatement()) {
            // Planned against the collection's columns and read through the alias
            try (ResultSet rows = statement.executeQuery("SELECT name, price FROM " + view + " LIMIT 1")) {
                assertTrue(rows.next());
                assertEquals("Desk lamp", rows.getString("name"));
                assertEquals(39.9, rows.getDouble("price"));
                assertFalse(rows.next());
            }

            SQLException unknown = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT nope FROM " + view));
            assertEquals("42S22", unknown.getSQLState());
        } finally {
            SampleCollections.deleteAliases(gateway, PREFIX);
        }
    }

    /**
     * The statement DataGrip's data editor sends when a collection is opened in the explorer:
     * the schema-qualified collection, the name it gives it, and a star under that name.
     */
    @Test
    void readsACollectionTheWayADataGripGridDoes() throws SQLException {
        String table = PREFIX + "products";

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT t.* FROM collections." + table + " t")) {
            assertEquals(List.of("_id", "created_at", "in_stock", "name", "price", "specs",
                "specs.color", "tags", "_payload"), labels(rows));

            int read = 0;
            while (rows.next()) read++;

            assertEquals(5, read, "every point, and no vectors, as a star reads them");
        }
    }

    @Test
    void readsANestedPayloadFieldQualifiedOrQuoted() throws SQLException {
        String table = PREFIX + "products";

        try (Statement statement = connection.createStatement()) {
            for (String item : List.of("specs.color", "t.\"specs.color\"", "\"specs.color\"")) {
                try (ResultSet rows = statement.executeQuery(
                    "SELECT " + item + " FROM " + table + " t LIMIT 1")) {
                    assertTrue(rows.next(), item);
                    assertEquals("black", rows.getString("specs.color"), item);
                }
            }

            SQLException misspelled = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT specs.colour FROM " + table));
            assertEquals("42S22", misspelled.getSQLState());
        }
    }

    @Test
    void describesProductColumns() throws SQLException {
        assertEquals(List.of(
            "_id VARCHAR", "created_at TIMESTAMP WITH TIME ZONE", "in_stock BOOLEAN", "name VARCHAR",
            "price DOUBLE", "specs JSON", "specs.color VARCHAR", "tags JSON", "_payload JSON",
            "_vector VECTOR(4)"), columns("products"));
    }

    @Test
    void describesArticleColumns() throws SQLException {
        assertEquals(List.of(
            "_id VARCHAR", "author JSON", "author.name VARCHAR", "lang VARCHAR", "title VARCHAR",
            "year BIGINT", "_payload JSON", "colbert MULTIVECTOR(2)", "dense VECTOR(4)",
            "keywords SPARSE_VECTOR"), columns("articles"));
    }

    @Test
    void describesEventColumnsFromSampledValues() throws SQLException {
        assertEquals(List.of(
            "_id VARCHAR", "count DOUBLE", "flag JSON", "id VARCHAR", "kind VARCHAR", "meta JSON",
            "_payload JSON", "_vector VECTOR(2)"), columns("events"));
    }

    @Test
    void filtersColumnsByName() throws SQLException {
        List<String> names = new ArrayList<>();

        try (ResultSet rows = metaData.getColumns(null, null, like(PREFIX + "products"), "spec%")) {
            while (rows.next()) {
                names.add(rows.getString("COLUMN_NAME"));
            }
        }

        assertEquals(List.of("specs", "specs.color"), names);
    }

    @Test
    void marksOnlyTheIdAsNotNull() throws SQLException {
        try (ResultSet rows = metaData.getColumns(null, null, like(PREFIX + "products"), "%")) {
            while (rows.next()) {
                boolean id = rows.getString("COLUMN_NAME").equals("_id");
                assertEquals(id ? "NO" : "YES", rows.getString("IS_NULLABLE"));
                assertEquals(id ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullable,
                    rows.getInt("NULLABLE"));
            }
        }
    }

    @Test
    void reportsTheIdAsPrimaryKey() throws SQLException {
        String table = PREFIX + "products";

        try (ResultSet rows = metaData.getPrimaryKeys(null, null, table)) {
            assertTrue(rows.next());
            assertEquals("_id", rows.getString("COLUMN_NAME"));
            assertEquals(1, rows.getShort("KEY_SEQ"));
            assertEquals(table + "_pkey", rows.getString("PK_NAME"));
            assertFalse(rows.next());
        }

        try (ResultSet rows = metaData.getPrimaryKeys(null, null, PREFIX + "missing")) {
            assertFalse(rows.next());
        }
    }

    @Test
    void reportsPayloadIndexesAsIndexes() throws SQLException {
        String table = PREFIX + "products";
        List<String> indexes = new ArrayList<>();

        try (ResultSet rows = metaData.getIndexInfo(null, null, table, false, true)) {
            while (rows.next()) {
                indexes.add(rows.getString("INDEX_NAME") + " " + rows.getString("COLUMN_NAME")
                    + (rows.getBoolean("NON_UNIQUE") ? "" : " unique"));
            }
        }

        assertEquals(List.of(
            table + "_pkey _id unique", "created_at_idx created_at", "name_idx name",
            "price_idx price", "specs.color_idx specs.color", "tags_idx tags"), indexes);
    }

    @Test
    void reportsThePointCountAsThePrimaryKeysCardinality() throws SQLException {
        try (ResultSet rows = metaData.getIndexInfo(null, null, PREFIX + "products", true, true)) {
            assertTrue(rows.next());
            assertEquals(5, rows.getLong("CARDINALITY"));
            assertFalse(rows.next());
        }
    }

    @Test
    void putsTheCollectionsInOneCatalogAndSchema() throws SQLException {
        try (ResultSet catalogs = metaData.getCatalogs(); ResultSet schemas = metaData.getSchemas()) {
            assertTrue(catalogs.next());
            assertEquals("qdrant", catalogs.getString("TABLE_CAT"));
            assertFalse(catalogs.next());

            assertTrue(schemas.next());
            assertEquals("collections", schemas.getString("TABLE_SCHEM"));
            assertEquals("qdrant", schemas.getString("TABLE_CATALOG"));
            assertTrue(schemas.next());
            assertEquals("system", schemas.getString("TABLE_SCHEM"), "the system tables' schema");
            assertEquals("qdrant", schemas.getString("TABLE_CATALOG"));
            assertFalse(schemas.next());
        }

        assertEquals("qdrant", connection.getCatalog());
        assertEquals("collections", connection.getSchema());

        try (ResultSet rows = metaData.getTables("some_catalog", null, "%", null)) {
            assertFalse(rows.next());
        }
    }

    @Test
    void reportsTheServer() throws SQLException {
        assertEquals("Qdrant", metaData.getDatabaseProductName());
        assertTrue(metaData.getDatabaseProductVersion().matches("\\d+\\.\\d+.*"),
            metaData.getDatabaseProductVersion());
        assertEquals(DriverInfo.NAME, metaData.getDriverName());
    }

    @Test
    void showsChangesToACollectionAfterARefresh() throws SQLException {
        String table = PREFIX + "changing";
        gateway.request(HttpMethod.PUT, "/collections/" + table,
            object("vectors", object("size", 2, "distance", "Dot")));

        try {
            upsert(table, 1, object("kind", "signup"));
            List<String> before =
                List.of("_id VARCHAR", "kind VARCHAR", "_payload JSON", "_vector VECTOR(2)");
            assertEquals(before, columns("changing"));

            upsert(table, 2, object("color", "red"));
            assertEquals(before, columns("changing"), "kept until a refresh");

            assertEquals(List.of(table), tables(table));
            assertEquals(List.of("_id VARCHAR", "color VARCHAR", "kind VARCHAR", "_payload JSON",
                "_vector VECTOR(2)"), columns("changing"));
        } finally {
            gateway.request(HttpMethod.DELETE, "/collections/" + table, null);
        }

        assertEquals(List.of(), tables(table));
        assertEquals(List.of(), columns("changing"));
    }

    @Test
    void findsAFieldAddedSinceTheColumnsWereKept() throws SQLException {
        String table = PREFIX + "growing";
        gateway.request(HttpMethod.PUT, "/collections/" + table,
            object("vectors", object("size", 2, "distance", "Dot")));

        try (Statement statement = connection.createStatement()) {
            upsert(table, 1, object("kind", "signup"));
            statement.executeQuery("SELECT kind FROM " + table).close();

            // Planned against the columns read again, not reported as an unknown column
            upsert(table, 2, object("color", "red"));
            try (ResultSet rows = statement.executeQuery("SELECT _id, color FROM " + table)) {
                List<String> colors = new ArrayList<>();
                while (rows.next()) {
                    colors.add(rows.getString("_id") + "=" + rows.getString("color"));
                }
                assertEquals(List.of("1=null", "2=red"), colors);
            }

            SQLException unknown = assertThrows(SQLException.class,
                () -> statement.executeQuery("SELECT nope FROM " + table));
            assertEquals("42S22", unknown.getSQLState());
        } finally {
            gateway.request(HttpMethod.DELETE, "/collections/" + table, null);
        }
    }

    private static void upsert(String collection, int id, ObjectNode payload) throws SQLException {
        gateway.request(HttpMethod.PUT, "/collections/" + collection + "/points?wait=true",
            object("points", array(object("id", id, "vector", array(0.1, 0.2), "payload", payload))));
    }

    /** Lists the tables matching a literal name, as a tool's Refresh does. */
    private static List<String> tables(String name) throws SQLException {
        return tables(like(name), (String[]) null);
    }

    /** Lists the tables and views of the given types matching a pattern. */
    private static List<String> tables(String pattern, String... types) throws SQLException {
        List<String> tables = new ArrayList<>();

        try (ResultSet rows = metaData.getTables(null, null, pattern, types)) {
            while (rows.next()) {
                tables.add(rows.getString("TABLE_NAME"));
            }
        }

        return tables;
    }

    /** Describes a table's columns as "name TYPE" or "name TYPE(size)" for vectors. */
    /** The column labels of a result, in order. */
    private static List<String> labels(ResultSet rows) throws SQLException {
        List<String> labels = new ArrayList<>();

        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
            labels.add(rows.getMetaData().getColumnLabel(column));
        }

        return labels;
    }

    private static List<String> columns(String table) throws SQLException {
        List<String> columns = new ArrayList<>();

        try (ResultSet rows = metaData.getColumns(null, null, like(PREFIX + table), "%")) {
            while (rows.next()) {
                String type = rows.getString("TYPE_NAME");
                int size = rows.getInt("COLUMN_SIZE");
                boolean vector = type.endsWith("VECTOR") && size > 0;
                columns.add(rows.getString("COLUMN_NAME") + " " + type + (vector ? "(" + size + ")" : ""));
            }
        }

        return columns;
    }

    /** Escapes the LIKE wildcards in a literal name; the test prefix contains underscores. */
    private static String like(String literal) throws SQLException {
        String escape = metaData.getSearchStringEscape();
        return literal.replace("_", escape + "_").replace("%", escape + "%");
    }
}
