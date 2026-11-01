package com.runaxsoftware.qdrant.jdbc.gateway;

import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.point;
import static com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.unnamedVector;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOption;
import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOptions;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.Request;
import com.runaxsoftware.qdrant.jdbc.support.Json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

class RequestOptionsTest {

    private FakeQdrantServer server;
    private QdrantGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = FakeQdrantServer.start().collection("docs", unnamedVector(2, "Cosine", Map.of()),
            List.of(point(1, Json.object("lang", "en"))));
        gateway = QdrantGateway.open("127.0.0.1", server.port(), false, null, Duration.ofSeconds(5),
            "test");
    }

    @AfterEach
    void stop() {
        gateway.close();
        server.close();
    }

    @Test
    void sendsNoOptionsUntilOneIsSet() throws SQLException {
        gateway.scroll(new ScrollRequest("docs", 1, null, false, false));
        delete();

        assertNull(last("/points/scroll").query());
        assertEquals("wait=true", last("/points/delete").query(), "writes wait unless told not to");
    }

    @Test
    void sendsReadOptionsWithReads() throws SQLException {
        gateway.options(RequestOptions.NONE
            .with(RequestOption.CONSISTENCY, "majority")
            .with(RequestOption.TIMEOUT, 5));

        gateway.scroll(new ScrollRequest("docs", 1, null, false, false));
        gateway.count("docs", null);
        gateway.query(new QueryRequest("docs", "", List.of(0.1, 0.2), 1, false, false));

        assertEquals("consistency=majority&timeout=5", last("/points/scroll").query());
        assertEquals("consistency=majority&timeout=5", last("/points/count").query());
        assertEquals("consistency=majority&timeout=5", last("/points/query").query());
    }

    @Test
    void sendsWriteOptionsWithWrites() throws SQLException {
        gateway.options(RequestOptions.NONE
            .with(RequestOption.WAIT, false)
            .with(RequestOption.ORDERING, "strong")
            .with(RequestOption.CONSISTENCY, "all"));

        delete();

        assertEquals("wait=false&ordering=strong", last("/points/delete").query(),
            "a read's consistency doesn't go with a write");
    }

    @Test
    void sendsSearchPrecisionInTheSearchBody() throws SQLException {
        gateway.options(RequestOptions.NONE
            .with(RequestOption.HNSW_EF, 128)
            .with(RequestOption.EXACT, true)
            .with(RequestOption.INDEXED_ONLY, false));

        gateway.query(new QueryRequest("docs", "", List.of(0.1, 0.2), 1, false, false));
        gateway.scroll(new ScrollRequest("docs", 1, null, false, false));

        assertEquals(Json.object("hnsw_ef", 128, "exact", true, "indexed_only", false),
            last("/points/query").body().get("params"));
        assertNull(last("/points/scroll").body().get("params"), "a scroll isn't a search");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "CONSISTENCY  | Majority | majority",
            "CONSISTENCY  | 2        | 2",
            "ORDERING     | STRONG   | strong",
            "WAIT         | false    | false",
            "TIMEOUT      | 30       | 30",
            "HNSW_EF      | 64       | 64",
            "EXACT        | TRUE     | true"})
    void readsValuesGivenAsText(RequestOption option, String given, String value) throws SQLException {
        assertEquals(value, option.value(given).toString());
    }

    @Test
    void readsNumbersAndBooleansAsThemselves() throws SQLException {
        assertEquals(3, RequestOption.CONSISTENCY.value(3L));
        assertEquals(10, RequestOption.TIMEOUT.value(10L));
        assertEquals(false, RequestOption.WAIT.value(false));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "CONSISTENCY | most",
            "ORDERING    | fast",
            "WAIT        | yes",
            "TIMEOUT     | 0",
            "TIMEOUT     | -5",
            "HNSW_EF     | lots"})
    void rejectsValuesQdrantDoesntTake(RequestOption option, String given) {
        assertEquals("22023", assertThrows(SQLException.class, () -> option.value(given)).getSQLState());
    }

    @Test
    void namesTheValuesAnOptionTakes() {
        SQLException error = assertThrows(SQLException.class, () -> RequestOption.ORDERING.value("fast"));

        assertEquals("qdrant.ordering takes medium, strong, weak, not 'fast'", error.getMessage());
        assertTrue(RequestOption.names().contains("qdrant.consistency"));
    }

    // The fake server has no delete endpoint: the request it records is what's checked
    private void delete() {
        assertThrows(SQLException.class,
            () -> gateway.deletePoints("docs", Json.object("must", Json.array())));
    }

    private Request last(String suffix) {
        List<Request> requests = server.requests();

        for (int i = requests.size() - 1; i >= 0; i--) {
            if (requests.get(i).path().endsWith(suffix)) return requests.get(i);
        }

        throw new AssertionError("No request to " + suffix);
    }
}
