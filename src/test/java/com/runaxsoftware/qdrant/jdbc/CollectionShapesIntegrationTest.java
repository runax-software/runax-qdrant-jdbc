package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.client.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;

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
 * Collections of unusual shapes, and how the sample size changes the columns, against a real
 * Qdrant. Needs {@code docker compose up -d}.
 */
@Tag("integration")
class CollectionShapesIntegrationTest {

    private static final String PREFIX =
        "jdbc_shapes_" + UUID.randomUUID().toString().substring(0, 8) + "_";
    private static final String MIXED_CASE = PREFIX + "Mixed-Case.v2";
    private static final String EMPTY = PREFIX + "empty";
    private static final String SPARSE_ONLY = PREFIX + "sparse_only";

    private static QdrantGateway gateway;

    @BeforeAll
    static void createCollections() throws SQLException {
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, PREFIX);

        gateway.await(gateway.sdk().createCollectionAsync(MIXED_CASE,
            VectorParams.newBuilder().setSize(2).setDistance(Distance.Dot).build()));
        gateway.await(gateway.sdk().createCollectionAsync(EMPTY,
            VectorParams.newBuilder().setSize(8).setDistance(Distance.Cosine).build()));
        gateway.await(gateway.sdk().createPayloadIndexAsync(EMPTY, "lang", PayloadSchemaType.Keyword,
            null, true, null, null));
        gateway.await(gateway.sdk().createPayloadIndexAsync(EMPTY, "year", PayloadSchemaType.Integer,
            null, true, null, null));
        gateway.await(gateway.sdk().createCollectionAsync(CreateCollection.newBuilder()
            .setCollectionName(SPARSE_ONLY)
            .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
                .putMap("bm25", SparseVectorParams.getDefaultInstance()))
            .build()));
    }

    @AfterAll
    static void deleteCollections() throws SQLException {
        if (gateway != null) {
            for (String name : List.of(MIXED_CASE, EMPTY, SPARSE_ONLY)) {
                if (gateway.await(gateway.sdk().collectionExistsAsync(name))) {
                    gateway.await(gateway.sdk().deleteCollectionAsync(name));
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
                "sparse_only"), tables);
        }
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
