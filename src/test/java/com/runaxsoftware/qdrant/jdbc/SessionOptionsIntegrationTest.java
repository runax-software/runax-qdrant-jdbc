package com.runaxsoftware.qdrant.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.runaxsoftware.qdrant.jdbc.gateway.QdrantGateway;
import com.runaxsoftware.qdrant.jdbc.support.SampleCollections;
import com.runaxsoftware.qdrant.jdbc.support.TestQdrant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sets every request option against real Qdrant, so what the driver sends with each request is
 * something Qdrant takes. Needs Qdrant: {@code docker compose up -d}.
 */
@Tag("integration")
class SessionOptionsIntegrationTest {

    private String products;
    private String prefix;
    private QdrantGateway gateway;
    private Connection connection;

    @BeforeEach
    void createCollections() throws SQLException {
        prefix = "jdbc_options_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        products = prefix + "products";
        gateway = TestQdrant.gateway();
        SampleCollections.create(gateway, prefix);
        connection = DriverManager.getConnection(TestQdrant.jdbcUrl(), TestQdrant.properties());
    }

    @AfterEach
    void deleteCollections() throws SQLException {
        connection.close();
        SampleCollections.delete(gateway, prefix);
        gateway.close();
    }

    @Test
    void readsWithEveryReadOption() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("SET qdrant.consistency = majority");
            statement.executeUpdate("SET qdrant.timeout = 10");

            assertEquals(List.of("2", "3", "4"), ids(statement, "SELECT _id FROM " + products
                + " WHERE price > 50"));
            assertEquals(List.of("5"), ids(statement, "SELECT COUNT(*) FROM " + products));

            statement.executeUpdate("SET qdrant.consistency = 1");
            assertEquals(List.of("5"), ids(statement, "SELECT COUNT(*) FROM " + products));
        }
    }

    @Test
    void searchesWithEverySearchOption() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("SET qdrant.hnsw_ef = 128");
            statement.executeUpdate("SET qdrant.exact = true");
            statement.executeUpdate("SET qdrant.indexed_only = false");

            assertEquals(List.of("1"), ids(statement, "SELECT _id FROM " + products
                + " ORDER BY similarity(_vector, [0.9, 0.1, 0.1, 0.2]) DESC LIMIT 1"));
        }
    }

    @Test
    void writesWithEveryWriteOption() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("SET qdrant.ordering = strong");
            statement.executeUpdate("SET qdrant.timeout = 10");
            assertEquals(1, statement.executeUpdate("DELETE FROM " + products + " WHERE _id = 1"));

            statement.executeUpdate("SET qdrant.ordering = DEFAULT");
            statement.executeUpdate("SET qdrant.wait = false");
            assertEquals(1, statement.executeUpdate("DELETE FROM " + products + " WHERE _id = 2"),
                "counted before the delete, which doesn't wait");
        }
    }

    private static List<String> ids(Statement statement, String sql) throws SQLException {
        var ids = new ArrayList<String>();

        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getString(1));
        }

        return ids;
    }
}
