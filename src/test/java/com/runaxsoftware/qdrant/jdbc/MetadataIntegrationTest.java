package com.runaxsoftware.qdrant.jdbc;

import static com.runaxsoftware.qdrant.jdbc.support.Json.array;
import static com.runaxsoftware.qdrant.jdbc.support.Json.object;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
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
    void listsNoTablesForOtherTableTypes() throws SQLException {
        try (ResultSet rows = metaData.getTables(null, null, "%", new String[] {"VIEW"})) {
            assertFalse(rows.next());
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

    private static void upsert(String collection, int id, ObjectNode payload) throws SQLException {
        gateway.request(HttpMethod.PUT, "/collections/" + collection + "/points?wait=true",
            object("points", array(object("id", id, "vector", array(0.1, 0.2), "payload", payload))));
    }

    /** Lists the tables matching a literal name, as a tool's Refresh does. */
    private static List<String> tables(String name) throws SQLException {
        List<String> tables = new ArrayList<>();

        try (ResultSet rows = metaData.getTables(null, null, like(name), null)) {
            while (rows.next()) {
                tables.add(rows.getString("TABLE_NAME"));
            }
        }

        return tables;
    }

    /** Describes a table's columns as "name TYPE" or "name TYPE(size)" for vectors. */
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
