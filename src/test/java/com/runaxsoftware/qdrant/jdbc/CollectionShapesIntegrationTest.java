package com.runaxsoftware.qdrant.jdbc;

import static com.runaxsoftware.qdrant.jdbc.support.Json.object;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Collections of unusual shapes, and how the sample size changes the columns, against a real
 * Qdrant. Needs {@code docker compose up -d}.
 */
@Tag("integration")
class CollectionShapesIntegrationTest {

    private static final String PREFIX =
        "jdbc_shapes_" + UUID.randomUUID().toString().substring(0, 8) + "_";
    private static final String MIXED_CASE = PREFIX + "Mixed-Case.v2";
    private static final String WITH_SPACE = PREFIX + "with space";
    private static final String EMPTY = PREFIX + "empty";
    private static final String SPARSE_ONLY = PREFIX + "sparse_only";
    private static final List<String> EXTRA = List.of(MIXED_CASE, WITH_SPACE, EMPTY, SPARSE_ONLY);

    private static QdrantGateway gateway;

    @BeforeAll
    static void createCollections() throws SQLException {
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, PREFIX);

        create(MIXED_CASE, object("vectors", object("size", 2, "distance", "Dot")));
        create(WITH_SPACE, object("vectors", object("size", 2, "distance", "Dot")));
        create(EMPTY, object("vectors", object("size", 8, "distance", "Cosine")));
        index(EMPTY, "lang", "keyword");
        index(EMPTY, "year", "integer");
        create(SPARSE_ONLY, object("sparse_vectors", object("bm25", object())));
    }

    @AfterAll
    static void deleteCollections() throws SQLException {
        if (gateway != null) {
            List<String> existing = gateway.listCollections();

            for (String name : EXTRA) {
                if (existing.contains(name)) {
                    gateway.request(HttpMethod.DELETE, "/collections/" + segment(name), null);
                }
            }

            SampleCollections.delete(gateway, PREFIX);
            gateway.close();
        }
    }

    @Test
    void keepsCollectionNamesExactly() throws SQLException {
        try (Connection connection = connect("")) {
            DatabaseMetaData metaData = connection.getMetaData();

            assertEquals(List.of(MIXED_CASE), tables(metaData, like(MIXED_CASE)));
            assertEquals(List.of(), tables(metaData, like(PREFIX + "mixed-case.v2")));
            assertEquals(List.of("_id", "_payload", "_vector"), columns(metaData, MIXED_CASE));
        }
    }

    @Test
    void readsCollectionsWhoseNamesNeedEncodingInAUrl() throws SQLException {
        try (Connection connection = connect("")) {
            DatabaseMetaData metaData = connection.getMetaData();

            assertEquals(List.of(WITH_SPACE), tables(metaData, like(WITH_SPACE)));
            assertEquals(List.of("_id", "_payload", "_vector"), columns(metaData, WITH_SPACE));
        }
    }

    @Test
    void describesAnEmptyCollectionFromItsIndexes() throws SQLException {
        try (Connection connection = connect("")) {
            assertEquals(List.of("_id", "lang", "year", "_payload", "_vector"),
                columns(connection.getMetaData(), EMPTY));
        }
    }

    @Test
    void describesACollectionWithOnlySparseVectors() throws SQLException {
        try (Connection connection = connect("")) {
            assertEquals(List.of("_id VARCHAR", "_payload JSON", "bm25 SPARSE_VECTOR"),
                typedColumns(connection.getMetaData(), SPARSE_ONLY));
        }
    }

    @Test
    void findsFieldsOnlyInThePointsItSamples() throws SQLException {
        // Qdrant returns points in ID order. Point 1 has kind, a whole-number count, a boolean
        // flag, and its own "id" payload field; later points add a fractional count, a text
        // flag, and meta.
        try (Connection one = connect("sampleSize=1"); Connection all = connect("sampleSize=100")) {
            assertEquals(List.of("_id VARCHAR", "count BIGINT", "flag BOOLEAN", "id VARCHAR",
                "kind VARCHAR", "_payload JSON", "_vector VECTOR"),
                typedColumns(one.getMetaData(), PREFIX + "events"));
            assertEquals(List.of("_id VARCHAR", "count DOUBLE", "flag JSON", "id VARCHAR", "kind VARCHAR",
                "meta JSON", "_payload JSON", "_vector VECTOR"),
                typedColumns(all.getMetaData(), PREFIX + "events"));
        }
    }

    @Test
    void usesOnlyIndexesWithoutSampling() throws SQLException {
        // Without a sample, the indexed tags field can't be seen to hold arrays, so it's typed
        // from its keyword index.
        try (Connection connection = connect("sampleSize=0")) {
            assertEquals(List.of("_id VARCHAR", "created_at TIMESTAMP WITH TIME ZONE", "name VARCHAR",
                "price DOUBLE", "specs.color VARCHAR", "tags VARCHAR", "_payload JSON",
                "_vector VECTOR"), typedColumns(connection.getMetaData(), PREFIX + "products"));
        }
    }

    @Test
    void listsColumnsOfEveryMatchingCollection() throws SQLException {
        try (Connection connection = connect("");
             ResultSet columns = connection.getMetaData().getColumns(null, null, like(PREFIX) + "%",
                 "\\_id")) {
            List<String> tables = new ArrayList<>();

            while (columns.next()) {
                tables.add(columns.getString("TABLE_NAME").substring(PREFIX.length()));
                assertEquals(1, columns.getInt("ORDINAL_POSITION"));
            }

            assertEquals(List.of("Mixed-Case.v2", "articles", "empty", "events", "products",
                "sparse_only", "with space"), tables);
        }
    }

    private static void create(String name, ObjectNode body) throws SQLException {
        gateway.request(HttpMethod.PUT, "/collections/" + segment(name), body);
    }

    private static void index(String collection, String field, String type) throws SQLException {
        gateway.request(HttpMethod.PUT, "/collections/" + segment(collection) + "/index?wait=true",
            object("field_name", field, "field_schema", type));
    }

    private static String segment(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Connection connect(String parameters) throws SQLException {
        String url = TestQdrant.jdbcUrl();

        if (!parameters.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + parameters;
        }

        return DriverManager.getConnection(url, TestQdrant.properties());
    }

    private static List<String> tables(DatabaseMetaData metaData, String pattern)
        throws SQLException {
        List<String> names = new ArrayList<>();

        try (ResultSet rows = metaData.getTables(null, null, pattern, null)) {
            while (rows.next()) {
                names.add(rows.getString("TABLE_NAME"));
            }
        }

        return names;
    }

    private static List<String> columns(DatabaseMetaData metaData, String table) throws SQLException {
        List<String> names = new ArrayList<>();

        try (ResultSet rows = metaData.getColumns(null, null, like(table), "%")) {
            while (rows.next()) {
                names.add(rows.getString("COLUMN_NAME"));
            }
        }

        return names;
    }

    /** Each column as "name TYPE". */
    private static List<String> typedColumns(DatabaseMetaData metaData, String table)
        throws SQLException {
        List<String> columns = new ArrayList<>();

        try (ResultSet rows = metaData.getColumns(null, null, like(table), "%")) {
            while (rows.next()) {
                columns.add(rows.getString("COLUMN_NAME") + " " + rows.getString("TYPE_NAME"));
            }
        }

        return columns;
    }

    private static String like(String literal) {
        return literal.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
    }
}
