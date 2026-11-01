package com.runaxsoftware.qdrant.jdbc.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadIndexType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetHit;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Ordering;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Document;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollPage;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ServerVersion;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.Endpoint;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer.Request;
import com.runaxsoftware.qdrant.jdbc.support.Json;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

class QdrantGatewayTest {

    private static final String USER_AGENT = "test-agent/1.0";
    private static final BigInteger LARGEST_ID = new BigInteger("18446744073709551615");
    private static final String UUID = "5c56c793-69f3-4fbf-87e6-c4bf54c28c26";

    private FakeQdrantServer server;
    private QdrantGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = FakeQdrantServer.start();
        gateway = open(null, Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        gateway.close();
        server.close();
    }

    // Server version

    @Test
    void readsTheServerVersionWithGet() throws SQLException {
        ServerVersion version = gateway.serverVersion();

        assertEquals(FakeQdrantServer.VERSION, version.version());
        assertEquals(1, version.major());
        assertEquals(20, version.minor());
        assertEquals(FakeQdrantServer.COMMIT, version.commit());

        Request request = server.requests().get(0);
        assertEquals("GET", request.method());
        assertEquals("/", request.path());
        assertNull(request.body());
    }

    @Test
    void readsTheServerVersionWithoutAnApiKey() throws SQLException {
        server.requireApiKey("right-key");

        assertEquals(FakeQdrantServer.VERSION, gateway.serverVersion().version());
    }

    @Test
    void readsAVersionWithoutACommit() throws Exception {
        server.failWithBody(Endpoint.VERSION, 200, "application/json",
            "{\"title\": \"qdrant - vector search engine\", \"version\": \"1.9.2\"}");

        ServerVersion version = gateway.serverVersion();

        assertEquals("1.9.2", version.version());
        assertEquals(9, version.minor());
        assertNull(version.commit());
    }

    // Collections

    @Test
    void listsCollectionsInServerOrder() throws SQLException {
        server.collection("b", plainCollection(), List.of())
            .collection("a", plainCollection(), List.of());

        assertEquals(List.of("b", "a"), gateway.listCollections());
        assertEquals("/collections", server.requests().get(0).path());
    }

    @Test
    void listsNoCollections() throws SQLException {
        assertEquals(List.of(), gateway.listCollections());
    }

    @Test
    void listsAliasesWithTheirCollections() throws SQLException {
        server.collection("products_v2", plainCollection(), List.of())
            .alias("products", "products_v2")
            .alias("current", "products_v2");

        assertEquals(List.of(new QdrantAlias("products", "products_v2"),
            new QdrantAlias("current", "products_v2")), gateway.listAliases());
        assertEquals("GET /aliases", server.requests().get(0).method() + " "
            + server.requests().get(0).path());
    }

    @Test
    void listsNoAliases() throws SQLException {
        assertEquals(List.of(), gateway.listAliases());
    }

    @Test
    void readsACollectionByAnAlias() throws SQLException {
        server.collection("products_v2", plainCollection(), points(3)).alias("products",
            "products_v2");

        assertEquals("products", gateway.collection("products").name());
        assertEquals(3, gateway.scroll(new ScrollRequest("products", 10, null, false, false))
            .points().size());
    }

    @Test
    void readsACollection() throws SQLException {
        server.collection("products", FakeQdrantServer.unnamedVector(4, "Cosine",
            Map.of("name", "keyword", "price", "float")), List.of());

        QdrantCollection collection = gateway.collection("products");

        assertEquals("products", collection.name());
        assertEquals(42, collection.pointsCount());
        assertEquals(List.of(new VectorField("", VectorKind.DENSE, 4, "Cosine")),
            collection.vectors());
        assertEquals(Map.of("name", PayloadIndexType.KEYWORD, "price", PayloadIndexType.FLOAT),
            collection.payloadIndexes());
    }

    @Test
    void readsNamedMultiAndSparseVectors() throws SQLException {
        server.collection("articles", FakeQdrantServer.collectionInfo(
            Json.object("dense", FakeQdrantServer.dense(4, "Dot"),
                "colbert", FakeQdrantServer.multivector(2, "Cosine")),
            Json.object("keywords", Json.object()),
            Map.of("future", "hyperdimensional")), List.of());

        QdrantCollection collection = gateway.collection("articles");

        assertEquals(List.of(
            new VectorField("dense", VectorKind.DENSE, 4, "Dot"),
            new VectorField("colbert", VectorKind.MULTIVECTOR, 2, "Cosine"),
            new VectorField("keywords", VectorKind.SPARSE, 0, null)), collection.vectors());
        assertEquals(PayloadIndexType.UNKNOWN, collection.payloadIndexes().get("future"));
    }

    @Test
    void reportsAMissingCollectionAsTableNotFound() {
        SQLException error = assertThrows(SQLException.class,
            () -> gateway.collection("missing"));

        assertEquals("42S02", error.getSQLState());
        assertEquals(404, error.getErrorCode());
        assertEquals("Qdrant: Not found: Collection `missing` doesn't exist!", error.getMessage());
    }

    @Test
    void readsCollectionsWithSpacesInTheirNames() throws SQLException {
        server.collection("my docs", plainCollection(), List.of());

        assertEquals("my docs", gateway.collection("my docs").name());
        assertEquals("/collections/my docs", server.requests().get(0).path());
    }

    @Test
    void encodesACollectionNameAsOnePathSegment() throws Exception {
        try (RecordingServer recording = new RecordingServer(200,
            "{\"result\": {\"config\": {\"params\": {}}}}")) {
            recording.gateway().collection("a/b c+d?é");

            assertEquals("/collections/a%2Fb%20c%2Bd%3F%C3%A9", recording.rawPaths().get(0));
        }
    }

    // Scrolling

    @Test
    void sendsTheScrollRequestAsAsked() throws SQLException {
        server.collection("events", plainCollection(), points(3));

        gateway.scroll(new ScrollRequest("events", 2, null, true, false));
        gateway.scroll(new ScrollRequest("events", 7, Json.node(2), false, true));

        List<JsonNode> requests = server.scrollRequests();
        assertEquals(Json.object("limit", 2, "with_payload", true, "with_vector", false),
            requests.get(0));
        assertEquals(Json.object("limit", 7, "with_payload", false, "with_vector", true,
            "offset", 2), requests.get(1));

        Request request = server.requests().get(0);
        assertEquals("POST", request.method());
        assertEquals("/collections/events/points/scroll", request.path());
    }

    @Test
    void sendsTheOrderOfAScroll() throws SQLException {
        server.collection("events", plainCollection(), points(3));

        gateway.scroll(new ScrollRequest("events", 2, null, true, false, null,
            new Ordering("price", true)));
        gateway.scroll(new ScrollRequest("events", 2, null, true, false, null,
            new Ordering("price", false)));

        List<JsonNode> requests = server.scrollRequests();
        assertEquals(Json.object("limit", 2, "with_payload", true, "with_vector", false,
            "order_by", Json.object("key", "price", "direction", "desc")), requests.get(0));
        assertEquals(Json.object("limit", 2, "with_payload", true, "with_vector", false,
            "order_by", Json.object("key", "price", "direction", "asc")), requests.get(1));
    }

    @Test
    void pagesThroughPoints() throws SQLException {
        server.collection("events", plainCollection(), points(5));
        List<Long> ids = new ArrayList<>();
        ScrollPage page = gateway.scroll(new ScrollRequest("events", 2, null, true, false));
        ids.addAll(ids(page));

        while (page.nextPageOffset() != null) {
            page = gateway.scroll(new ScrollRequest("events", 2, page.nextPageOffset(), true,
                false));
            ids.addAll(ids(page));
        }

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids);
        assertEquals(3, server.scrollRequests().size());
    }

    @Test
    void endsWithoutANextPageOffset() throws SQLException {
        server.collection("events", plainCollection(), points(2));

        ScrollPage page = gateway.scroll(new ScrollRequest("events", 10, null, true, false));

        assertEquals(2, page.points().size());
        assertNull(page.nextPageOffset());
    }

    @Test
    void pagesPastTheLargestUnsignedId() throws SQLException {
        server.collection("events", plainCollection(), List.of(
            FakeQdrantServer.point(1, Json.object("kind", "signup")),
            FakeQdrantServer.point(LARGEST_ID, Json.object("kind", "refund")),
            FakeQdrantServer.point(UUID, Json.object())));

        ScrollPage first = gateway.scroll(new ScrollRequest("events", 1, null, true, false));
        assertEquals(LARGEST_ID, first.nextPageOffset().bigIntegerValue());

        ScrollPage second = gateway.scroll(new ScrollRequest("events", 1, first.nextPageOffset(),
            true, false));
        assertEquals(LARGEST_ID, second.points().get(0).id().bigIntegerValue());
        assertEquals(LARGEST_ID, server.scrollRequests().get(1).get("offset").bigIntegerValue());
        assertEquals(UUID, second.nextPageOffset().asText());
    }

    @Test
    void readsPointsAsJson() throws SQLException {
        server.collection("events", plainCollection(), List.of(
            FakeQdrantServer.point(LARGEST_ID, Json.object("kind", "refund"),
                Json.array(0.5, 0.5)),
            FakeQdrantServer.point(UUID, Json.object())));

        List<Point> withPayload = gateway.scroll(new ScrollRequest("events", 10, null, true,
            false)).points();

        assertEquals(LARGEST_ID, withPayload.get(0).id().bigIntegerValue());
        assertEquals("refund", withPayload.get(0).payload().get("kind").asText());
        assertNull(withPayload.get(0).vector());
        assertEquals(UUID, withPayload.get(1).id().asText());
        assertTrue(withPayload.get(1).payload().isEmpty());

        List<Point> withVector = gateway.scroll(new ScrollRequest("events", 10, null, false,
            true)).points();

        assertNull(withVector.get(0).payload());
        assertEquals(Json.array(0.5, 0.5), withVector.get(0).vector());
    }

    @Test
    void reportsScrollingAMissingCollection() {
        SQLException error = assertThrows(SQLException.class,
            () -> gateway.scroll(new ScrollRequest("missing", 10, null, true, false)));

        assertEquals("42S02", error.getSQLState());
    }

    // Searching

    @Test
    void sendsTheFilterOfAScrollAndASearch() throws SQLException {
        server.collection("events", plainCollection(), points(3));
        JsonNode filter = Json.object("must", Json.array(Json.object("has_id", Json.array(1))));

        gateway.scroll(new ScrollRequest("events", 2, null, false, false, filter));
        gateway.query(new QueryRequest("events", "", List.of(0.1), 2, false, false, filter));

        assertEquals(filter, server.scrollRequests().get(0).get("filter"));
        assertEquals(filter, server.queryRequests().get(0).get("filter"));
    }

    @Test
    void sendsADocumentForQdrantToEmbed() throws SQLException {
        server.collection("events", plainCollection(), points(3));

        gateway.query(new QueryRequest("events", "keywords", List.of(), 2, false, false, null,
            new Document("refund policy", "qdrant/bm25")));

        assertEquals(Json.object("limit", 2, "with_payload", false, "with_vector", false,
            "query", Json.object("text", "refund policy", "model", "qdrant/bm25"),
            "using", "keywords"), server.queryRequests().get(0));
    }

    @Test
    void sendsTheSearchAsAsked() throws SQLException {
        server.collection("events", plainCollection(), points(3));

        gateway.query(new QueryRequest("events", "dense", List.of(0.1, 0.2), 2, true, false));
        gateway.query(new QueryRequest("events", "", List.of(1.0, -0.5), 7, false, true));

        List<JsonNode> requests = server.queryRequests();
        assertEquals(Json.object("limit", 2, "with_payload", true, "with_vector", false,
            "query", Json.object("nearest", Json.array(0.1, 0.2)), "using", "dense"),
            requests.get(0));
        assertEquals(Json.object("limit", 7, "with_payload", false, "with_vector", true,
            "query", Json.object("nearest", Json.array(1.0, -0.5))), requests.get(1),
            "an unnamed vector is the one Qdrant searches when none is named");

        Request request = server.requests().get(0);
        assertEquals("POST", request.method());
        assertEquals("/collections/events/points/query", request.path());
    }

    /**
     * The Query API's {@code offset} is a row count, unlike a scroll's, so the gateway sends it
     * as the statement wrote it and Qdrant answers with the points after it. A search that skips
     * nothing sends no offset at all.
     */
    @Test
    void sendsTheOffsetOfASearch() throws SQLException {
        server.collection("events", plainCollection(), points(5));

        List<ScoredPoint> found = gateway.query(
            new QueryRequest("events", "", List.of(0.1), 2, 2, false, false, null));

        assertEquals(Json.object("limit", 2, "with_payload", false, "with_vector", false,
            "query", Json.object("nearest", Json.array(0.1)), "offset", 2),
            server.queryRequests().get(0));
        assertEquals(List.of(3L, 4L),
            found.stream().map(point -> point.point().id().longValue()).toList());

        gateway.query(new QueryRequest("events", "", List.of(0.1), 2, 0, false, false, null));
        assertTrue(server.queryRequests().get(1).path("offset").isMissingNode(),
            "a search that skips nothing starts at the closest point");
    }

    @Test
    void readsTheScoredPointsOfASearch() throws SQLException {
        server.collection("events", plainCollection(), points(3));

        List<ScoredPoint> found = gateway.query(
            new QueryRequest("events", "", List.of(0.1), 2, true, false));

        assertEquals(2, found.size());
        assertEquals(1L, found.get(0).point().id().longValue());
        assertEquals(1.0, found.get(0).score());
        assertEquals(0.9, found.get(1).score());
        assertEquals(2L, found.get(1).point().id().longValue());
    }

    @Test
    void readsASearchThatFoundNothing() throws SQLException {
        server.collection("empty", plainCollection(), List.of());

        assertEquals(List.of(),
            gateway.query(new QueryRequest("empty", "", List.of(0.1), 5, true, false)));
    }

    @Test
    void reportsSearchingAMissingCollection() {
        SQLException error = assertThrows(SQLException.class,
            () -> gateway.query(new QueryRequest("missing", "", List.of(0.1), 5, true, false)));

        assertEquals("42S02", error.getSQLState());
    }

    // Counting

    @Test
    void countsThePointsOfACollection() throws SQLException {
        server.collection("events", plainCollection(), points(3));

        assertEquals(3L, gateway.count("events", null));

        assertEquals(List.of(Json.object("exact", true)), server.countRequests(),
            "an exact count, since an estimate answers a different number each time");

        Request request = server.requests().get(0);
        assertEquals("POST", request.method());
        assertEquals("/collections/events/points/count", request.path());
    }

    @Test
    void countsACollectionWithoutPoints() throws SQLException {
        server.collection("empty", plainCollection(), List.of());

        assertEquals(0L, gateway.count("empty", null));
    }

    @Test
    void countsACollectionUnderAnAliassName() throws SQLException {
        server.collection("events", plainCollection(), points(3)).alias("current", "events");

        assertEquals(3L, gateway.count("current", null));
    }

    @Test
    void reportsCountingAMissingCollection() {
        SQLException error = assertThrows(SQLException.class, () -> gateway.count("missing", null));

        assertEquals("42S02", error.getSQLState());
    }

    // Faceting

    @Test
    void sendsTheFacetAsAsked() throws SQLException {
        server.collection("events", plainCollection(), points(3));
        JsonNode filter = Json.object("must", Json.array(
            Json.object("key", "lang", "match", Json.object("value", "en"))));

        gateway.facet(new FacetRequest("events", "lang", 5, null));
        gateway.facet(new FacetRequest("events", "lang", 7, filter));

        List<JsonNode> requests = server.facetRequests();
        assertEquals(Json.object("key", "lang", "limit", 5, "exact", true), requests.get(0));
        assertEquals(Json.object("key", "lang", "limit", 7, "exact", true, "filter", filter),
            requests.get(1));

        Request request = server.requests().get(0);
        assertEquals("POST", request.method());
        assertEquals("/collections/events/facet", request.path());
    }

    @Test
    void readsTheCountedValuesOfAFacet() throws SQLException {
        server.collection("events", plainCollection(), List.of(
            FakeQdrantServer.point(1, Json.object("lang", "en")),
            FakeQdrantServer.point(2, Json.object("lang", "en")),
            FakeQdrantServer.point(3, Json.object("lang", "de")),
            FakeQdrantServer.point(4, Json.object())));

        List<FacetHit> hits = gateway.facet(new FacetRequest("events", "lang", 10, null));

        assertEquals(2, hits.size());
        assertEquals("en", hits.get(0).value().asText());
        assertEquals(2, hits.get(0).count());
        assertEquals("de", hits.get(1).value().asText());
        assertEquals(1, hits.get(1).count(), "the point without the field is in no group");
    }

    @Test
    void readsAFacetThatCountedNothing() throws SQLException {
        server.collection("empty", plainCollection(), List.of());

        assertEquals(List.of(), gateway.facet(new FacetRequest("empty", "lang", 10, null)));
    }

    @Test
    void reportsFacetingAMissingCollection() {
        SQLException error = assertThrows(SQLException.class,
            () -> gateway.facet(new FacetRequest("missing", "lang", 10, null)));

        assertEquals("42S02", error.getSQLState());
    }

    // Any endpoint

    @Test
    void returnsTheResultOfAnyEndpoint() throws SQLException {
        server.collection("a", plainCollection(), List.of());

        JsonNode result = gateway.request(HttpMethod.GET, "/collections", null);

        assertEquals(Json.parse("{\"collections\": [{\"name\": \"a\"}]}"), result);
    }

    @Test
    void sendsABodyToAnyEndpoint() throws SQLException {
        server.collection("events", plainCollection(), points(3));

        JsonNode result = gateway.request(HttpMethod.POST, "/collections/events/points/scroll",
            Json.object("limit", 1));

        assertEquals(1, result.get("points").size());
        assertEquals(Json.object("limit", 1), server.scrollRequests().get(0));
    }

    @Test
    void reportsARouteQdrantDoesNotHave() {
        // Qdrant answers POST / with an empty 404: the version is only at GET /
        SQLException error = assertThrows(SQLException.class,
            () -> gateway.request(HttpMethod.POST, "/", null));

        assertEquals("42S02", error.getSQLState());
        assertEquals("Qdrant: HTTP 404", error.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"POST, POST", "GET, GET", "PUT, PUT", "PATCH, PATCH", "DELETE, DELETE"})
    void namesEachHttpMethod(HttpMethod method, String name) {
        assertEquals(name, method.method());
    }

    // Endpoints that answer in text rather than JSON

    @Test
    void returnsTheTextOfAnEndpointThatAnswersInText() throws SQLException {
        assertEquals(FakeQdrantServer.METRICS, gateway.requestText("/metrics"));
    }

    @Test
    void asksForTextFromAnEndpointThatAnswersInText() throws Exception {
        try (RecordingServer recording = new RecordingServer(200, "app_info 1")) {
            assertEquals("app_info 1", recording.gateway().requestText("/metrics"));

            Headers headers = recording.headers().get(0);
            assertEquals("text/plain", headers.getFirst("Accept"));
            assertEquals(USER_AGENT, headers.getFirst("User-Agent"));
            assertNull(headers.getFirst("Content-Type"));
        }
    }

    @Test
    void reportsWhatQdrantReportsAboutAnEndpointThatAnswersInText() {
        server.fail(Endpoint.METRICS, 500, "Service internal error");

        SQLException error = assertThrows(SQLException.class,
            () -> gateway.requestText("/metrics"));

        assertEquals("HY000", error.getSQLState());
        assertEquals("Qdrant: Service internal error", error.getMessage());
    }

    // Headers

    @Test
    void sendsTheApiKeyOnlyWhenSet() throws Exception {
        gateway.listCollections();

        try (QdrantGateway withKey = open("secret", Duration.ofSeconds(5));
             QdrantGateway withEmptyKey = open("", Duration.ofSeconds(5))) {
            withKey.listCollections();
            withEmptyKey.listCollections();
        }

        assertEquals(List.of("", "secret", ""), server.apiKeys());
    }

    @Test
    void sendsTheUserAgent() throws SQLException {
        gateway.listCollections();

        assertEquals(USER_AGENT, server.userAgents().get(0));
    }

    @Test
    void asksForJsonAndLabelsJsonBodies() throws Exception {
        try (RecordingServer recording = new RecordingServer(200,
            "{\"result\": {\"points\": [], \"next_page_offset\": null}}")) {
            recording.gateway().scroll(new ScrollRequest("events", 1, null, true, false));
            recording.gateway().request(HttpMethod.GET, "/collections", null);

            Headers post = recording.headers().get(0);
            assertEquals("application/json", post.getFirst("Accept"));
            assertEquals("application/json", post.getFirst("Content-Type"));
            assertEquals(USER_AGENT, post.getFirst("User-Agent"));
            assertFalse(post.containsKey("api-key"));

            Headers get = recording.headers().get(1);
            assertEquals("application/json", get.getFirst("Accept"));
            assertNull(get.getFirst("Content-Type"));
        }
    }

    // Errors

    @Test
    void rejectsAWrongApiKeyWithoutRepeatingIt() throws Exception {
        server.requireApiKey("right-key");

        try (QdrantGateway wrong = open("wrong-key", Duration.ofSeconds(5))) {
            SQLInvalidAuthorizationSpecException error = assertThrows(
                SQLInvalidAuthorizationSpecException.class, wrong::listCollections);

            assertEquals("28000", error.getSQLState());
            assertEquals(401, error.getErrorCode());
            assertEquals("Qdrant rejected the API key (put it in the password field): "
                + "Invalid API key or JWT", error.getMessage());
            assertFalse(error.getMessage().contains("wrong-key"));
        }
    }

    @Test
    void reportsAMissingApiKeyWithQdrantsPlainTextMessage() {
        server.requireApiKey("right-key");

        SQLInvalidAuthorizationSpecException error = assertThrows(
            SQLInvalidAuthorizationSpecException.class, gateway::listCollections);

        assertTrue(error.getMessage().endsWith(
            "Must provide an API key or an Authorization bearer token"), error.getMessage());
    }

    @Test
    void treatsForbiddenLikeAWrongApiKey() {
        server.failWithBody(Endpoint.COLLECTIONS, 403, "text/plain", "Forbidden");

        SQLInvalidAuthorizationSpecException error = assertThrows(
            SQLInvalidAuthorizationSpecException.class, gateway::listCollections);

        assertEquals("28000", error.getSQLState());
        assertEquals(403, error.getErrorCode());
        assertTrue(error.getMessage().contains("password field"), error.getMessage());
        assertTrue(error.getMessage().endsWith("Forbidden"), error.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "400, 42000",
        "404, 42S02",
        "409, 42000",
        "422, 42000",
        "429, 42000",
        "500, HY000",
        "502, HY000",
        "503, HY000"})
    void mapsHttpStatusesToSqlStates(int status, String sqlState) {
        server.fail(Endpoint.COLLECTIONS, status, "details from Qdrant");

        SQLException error = assertThrows(SQLException.class, gateway::listCollections);

        assertEquals(SQLException.class, error.getClass());
        assertEquals(sqlState, error.getSQLState());
        assertEquals(status, error.getErrorCode());
        assertEquals("Qdrant: details from Qdrant", error.getMessage());
    }

    @Test
    void includesQdrantsMessage() {
        server.fail(Endpoint.COLLECTION, 400, "Wrong input: bad filter");

        SQLException error = assertThrows(SQLException.class, () -> gateway.collection("x"));

        assertEquals("Qdrant: Wrong input: bad filter", error.getMessage());
    }

    @Test
    void usesAPlainTextErrorBodyAsTheMessage() {
        server.failWithBody(Endpoint.COLLECTIONS, 500, "text/plain", "  something broke\n");

        SQLException error = assertThrows(SQLException.class, gateway::listCollections);

        assertEquals("Qdrant: something broke", error.getMessage());
    }

    @Test
    void usesAJsonErrorBodyWithoutAStatusErrorAsText() {
        server.failWithBody(Endpoint.COLLECTIONS, 500, "application/json", "{\"oops\": 1}");

        SQLException error = assertThrows(SQLException.class, gateway::listCollections);

        assertEquals("Qdrant: {\"oops\": 1}", error.getMessage());
    }

    @Test
    void cutsLongErrorPagesShort() {
        String page = "<html>" + "x".repeat(1000) + "</html>";
        server.failWithBody(Endpoint.COLLECTIONS, 502, "text/html", page);

        SQLException error = assertThrows(SQLException.class, gateway::listCollections);

        assertEquals("Qdrant: " + page.substring(0, 500) + "...", error.getMessage());
        assertEquals("HY000", error.getSQLState());
    }

    @Test
    void namesTheStatusWhenTheErrorHasNoBody() {
        server.failWithBody(Endpoint.COLLECTIONS, 503, "text/plain", "");

        SQLException error = assertThrows(SQLException.class, gateway::listCollections);

        assertEquals("Qdrant: HTTP 503", error.getMessage());
    }

    @Test
    void reportsAnAnswerThatIsNotJson() {
        server.failWithBody(Endpoint.COLLECTIONS, 200, "text/html", "<html>hello</html>");

        SQLException error = assertThrows(SQLException.class, gateway::listCollections);

        assertEquals("08S01", error.getSQLState());
        assertEquals("Qdrant sent a response that isn't JSON", error.getMessage());
    }

    @Test
    void reportsAnAnswerWithoutAResult() {
        server.failWithBody(Endpoint.COLLECTIONS, 200, "application/json",
            "{\"status\": \"ok\", \"time\": 0.0}");

        SQLException error = assertThrows(SQLException.class, gateway::listCollections);

        assertEquals("08S01", error.getSQLState());
        assertEquals("Qdrant sent a response without a result", error.getMessage());
    }

    @Test
    void timesOutSlowAnswers() throws Exception {
        server.delay(Endpoint.COLLECTIONS, Duration.ofSeconds(3));

        try (QdrantGateway impatient = open(null, Duration.ofMillis(300))) {
            SQLTimeoutException error = assertThrows(SQLTimeoutException.class,
                impatient::listCollections);

            assertEquals("HYT00", error.getSQLState());
            assertTrue(error.getMessage().contains("didn't answer in time"), error.getMessage());
            assertTrue(error.getMessage().contains(impatient.baseUrl()), error.getMessage());
        }
    }

    @Test
    void reportsAnUnreachableServerWithAHint() throws Exception {
        int port = server.port();
        server.close();

        try (QdrantGateway unreachable = QdrantGateway.open("localhost", port, false, null,
            Duration.ofSeconds(5), USER_AGENT)) {
            SQLNonTransientConnectionException error = assertThrows(
                SQLNonTransientConnectionException.class, unreachable::listCollections);

            assertEquals("08001", error.getSQLState());
            assertTrue(error.getMessage().contains("http://localhost:" + port),
                error.getMessage());
            assertTrue(error.getMessage().contains("REST port (6333 by default)"),
                error.getMessage());
            assertInstanceOf(IOException.class, error.getCause());
        }
    }

    @Test
    void reportsAServerThatHangsUpAsABrokenConnection() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread hangUp = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        client.getInputStream().read(new byte[1024]);
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            hangUp.setDaemon(true);
            hangUp.start();

            try (QdrantGateway broken = QdrantGateway.open("localhost", socket.getLocalPort(),
                false, null, Duration.ofSeconds(5), USER_AGENT)) {
                SQLNonTransientConnectionException error = assertThrows(
                    SQLNonTransientConnectionException.class, broken::listCollections);

                assertEquals("08006", error.getSQLState());
                assertTrue(error.getMessage().startsWith("Request to Qdrant at "
                    + broken.baseUrl() + " failed"), error.getMessage());
            }
        }
    }

    @Test
    void reportsAnInterruptedWaitAndKeepsTheInterrupt() {
        server.delay(Endpoint.COLLECTIONS, Duration.ofSeconds(2));
        Thread.currentThread().interrupt();

        try {
            SQLException error = assertThrows(SQLException.class, gateway::listCollections);

            assertEquals("HY008", error.getSQLState());
            assertInstanceOf(InterruptedException.class, error.getCause());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is restored");
        } finally {
            Thread.interrupted();
        }
    }

    // Address and lifecycle

    @Test
    void buildsTheBaseUrlFromHostPortAndTls() {
        assertEquals("http://localhost:" + server.port(), gateway.baseUrl());
        assertEquals("https://qdrant.example.com:443", QdrantGateway.open("qdrant.example.com",
            443, true, null, Duration.ofSeconds(1), USER_AGENT).baseUrl());
        assertEquals("http://[::1]:6333", QdrantGateway.open("::1", 6333, false, null,
            Duration.ofSeconds(1), USER_AGENT).baseUrl());
    }

    @Test
    void usesQdrantsRestPortByDefault() {
        assertEquals(6333, QdrantGateway.DEFAULT_PORT);
    }

    @Test
    void closesHarmlesslyMoreThanOnce() {
        assertDoesNotThrow(gateway::close);
        assertDoesNotThrow(gateway::close);
    }

    // Helpers

    private QdrantGateway open(String apiKey, Duration timeout) {
        return QdrantGateway.open("localhost", server.port(), false, apiKey, timeout, USER_AGENT);
    }

    private static ObjectNode plainCollection() {
        return FakeQdrantServer.unnamedVector(2, "Dot", Map.of());
    }

    private static List<ObjectNode> points(int count) {
        List<ObjectNode> points = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            points.add(FakeQdrantServer.point(i, Json.object("n", i)));
        }

        return points;
    }

    private static List<Long> ids(ScrollPage page) {
        return page.points().stream().map(point -> point.id().asLong()).toList();
    }

    /**
     * An HTTP server that answers every request the same way and keeps each request's raw path
     * and headers, for what {@link FakeQdrantServer} doesn't record.
     */
    private static final class RecordingServer implements AutoCloseable {

        private final HttpServer http;
        private final List<String> rawPaths = new CopyOnWriteArrayList<>();
        private final List<Headers> headers = new CopyOnWriteArrayList<>();
        private final QdrantGateway gateway;

        RecordingServer(int status, String body) throws IOException {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            http.createContext("/", exchange -> {
                rawPaths.add(exchange.getRequestURI().getRawPath());
                headers.add(exchange.getRequestHeaders());

                try (InputStream in = exchange.getRequestBody()) {
                    in.readAllBytes();
                }

                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);

                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            http.start();
            gateway = QdrantGateway.open("localhost", http.getAddress().getPort(), false, null,
                Duration.ofSeconds(5), USER_AGENT);
        }

        QdrantGateway gateway() {
            return gateway;
        }

        List<String> rawPaths() {
            return rawPaths;
        }

        List<Headers> headers() {
            return headers;
        }

        @Override
        public void close() {
            gateway.close();
            http.stop(0);
        }
    }
}
