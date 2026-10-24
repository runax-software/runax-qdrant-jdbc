package com.runaxsoftware.qdrant.jdbc.client;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.util.concurrent.SettableFuture;
import com.runaxsoftware.qdrant.jdbc.support.FakeQdrantServer;
import io.grpc.Status;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

class QdrantGatewayTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private FakeQdrantServer server;
    private QdrantGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = FakeQdrantServer.start();
        gateway = open(null, Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() throws Exception {
        gateway.close();
        server.close();
    }

    @Test
    void readsTheServerVersion() throws SQLException {
        ServerInfo info = gateway.serverInfo();

        assertEquals("qdrant - vector search engine", info.title());
        assertEquals(FakeQdrantServer.VERSION, info.version());
    }

    @Test
    void listsCollectionsInServerOrder() throws SQLException {
        server.collection("b", plainCollection(), List.of())
            .collection("a", plainCollection(), List.of());

        assertEquals(List.of("b", "a"), gateway.listCollections());
    }

    @Test
    void readsCollectionInfo() throws SQLException {
        server.collection("products", FakeQdrantServer.unnamedVector(4, Distance.Cosine,
            Map.of("name", PayloadSchemaType.Keyword)), List.of());

        CollectionInfo info = gateway.collectionInfo("products");

        assertEquals("products", info.name());
        assertEquals(42, info.pointsCount());
        assertEquals(List.of(new VectorField("", VectorKind.DENSE, 4, "Cosine")), info.vectors());
        assertEquals(Map.of("name", "keyword"), info.payloadIndexes());
    }

    @Test
    void reportsAMissingCollectionAsTableNotFound() {
        SQLException error = assertThrows(SQLException.class,
            () -> gateway.collectionInfo("missing"));

        assertEquals("42S02", error.getSQLState());
        assertTrue(error.getMessage().contains("missing"), error.getMessage());
    }

    @Test
    void sendsTheScrollRequestAsAsked() throws SQLException {
        server.collection("events", plainCollection(), points(3));

        gateway.scroll(new ScrollRequest("events", 2, null, true, false));
        gateway.scroll(new ScrollRequest("events", 7, JSON.numberNode(2), false, true));

        ScrollPoints first = server.scrollRequests().get(0);
        assertEquals("events", first.getCollectionName());
        assertEquals(2, first.getLimit());
        assertFalse(first.hasOffset());
        assertTrue(first.getWithPayload().getEnable());
        assertFalse(first.getWithVectors().getEnable());

        ScrollPoints second = server.scrollRequests().get(1);
        assertEquals(7, second.getLimit());
        assertEquals(id(2), second.getOffset());
        assertFalse(second.getWithPayload().getEnable());
        assertTrue(second.getWithVectors().getEnable());
    }

    @Test
    void pagesThroughPoints() throws SQLException {
        server.collection("events", plainCollection(), points(5));
        List<Long> ids = new ArrayList<>();
        ScrollPage page = gateway.scroll(new ScrollRequest("events", 2, null, true, false));
        ids.addAll(ids(page));

        while (page.nextPageOffset() != null) {
            page = gateway.scroll(new ScrollRequest("events", 2, page.nextPageOffset(), true, false));
            ids.addAll(ids(page));
        }

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids);
        assertEquals(3, server.scrollRequests().size());
    }

    @Test
    void convertsPointsToJson() throws SQLException {
        UUID uuid = UUID.fromString("5c56c793-69f3-4fbf-87e6-c4bf54c28c26");
        server.collection("events", plainCollection(), List.of(
            FakeQdrantServer.point(id(-1L), Map.of("kind", value("refund"))),
            FakeQdrantServer.point(id(uuid), Map.of())));

        List<Point> points = gateway.scroll(new ScrollRequest("events", 10, null, true, false))
            .points();

        assertEquals(new BigInteger("18446744073709551615"), points.get(0).id().bigIntegerValue());
        assertEquals("refund", points.get(0).payload().get("kind").asText());
        assertNull(points.get(0).vector());
        assertEquals(uuid.toString(), points.get(1).id().asText());
        assertTrue(points.get(1).payload().isEmpty());
    }

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

        assertTrue(server.userAgents().get(0).startsWith("test-agent/1.0"),
            server.userAgents().get(0));
    }

    @Test
    void rejectsAWrongApiKeyWithoutRepeatingIt() throws Exception {
        server.requireApiKey("right-key");

        try (QdrantGateway wrong = open("wrong-key", Duration.ofSeconds(5))) {
            SQLInvalidAuthorizationSpecException error = assertThrows(
                SQLInvalidAuthorizationSpecException.class, wrong::listCollections);

            assertEquals("28000", error.getSQLState());
            assertTrue(error.getMessage().contains("password field"), error.getMessage());
            assertFalse(error.getMessage().contains("wrong-key"));
            assertDoesNotThrow(wrong::serverInfo, "health checks need no key");
        }
    }

    @ParameterizedTest
    @MethodSource("statusCodes")
    void mapsGrpcStatusCodesToSqlExceptions(Status.Code code, Class<? extends SQLException> type,
                                            String sqlState) {
        server.fail("List", Status.fromCode(code).withDescription("details from Qdrant"));

        SQLException error = assertThrows(SQLException.class, gateway::listCollections);

        assertInstanceOf(type, error);
        assertEquals(sqlState, error.getSQLState());
        assertEquals(code.value(), error.getErrorCode());
        assertNotNull(error.getCause());
    }

    static Stream<Arguments> statusCodes() {
        return Stream.of(
            Arguments.of(Status.Code.UNAVAILABLE, SQLNonTransientConnectionException.class, "08001"),
            Arguments.of(Status.Code.UNAUTHENTICATED, SQLInvalidAuthorizationSpecException.class, "28000"),
            Arguments.of(Status.Code.PERMISSION_DENIED, SQLInvalidAuthorizationSpecException.class, "28000"),
            Arguments.of(Status.Code.DEADLINE_EXCEEDED, SQLTimeoutException.class, "HYT00"),
            Arguments.of(Status.Code.CANCELLED, SQLException.class, "HY008"),
            Arguments.of(Status.Code.NOT_FOUND, SQLException.class, "42S02"),
            Arguments.of(Status.Code.INVALID_ARGUMENT, SQLException.class, "42000"),
            Arguments.of(Status.Code.FAILED_PRECONDITION, SQLException.class, "42000"),
            Arguments.of(Status.Code.OUT_OF_RANGE, SQLException.class, "42000"),
            Arguments.of(Status.Code.INTERNAL, SQLException.class, "HY000"),
            Arguments.of(Status.Code.UNKNOWN, SQLException.class, "HY000"),
            Arguments.of(Status.Code.RESOURCE_EXHAUSTED, SQLException.class, "HY000"),
            Arguments.of(Status.Code.UNIMPLEMENTED, SQLException.class, "HY000"));
    }

    @Test
    void includesQdrantsMessage() {
        server.fail("Get", Status.INVALID_ARGUMENT.withDescription("Wrong input: bad filter"));

        SQLException error = assertThrows(SQLException.class, () -> gateway.collectionInfo("x"));

        assertEquals("Qdrant: Wrong input: bad filter", error.getMessage());
    }

    @Test
    void keepsOnlyTheFirstLineOfGrpcDetails() {
        server.fail("List", Status.UNAVAILABLE.withDescription(
            "io exception\nChannel Pipeline: [SslHandler#0, ProtocolNegotiators$ClientTlsHandler#0]"));

        SQLException error = assertThrows(SQLException.class, gateway::listCollections);

        assertTrue(error.getMessage().contains("localhost:" + server.port() + ": io exception."),
            error.getMessage());
        assertFalse(error.getMessage().contains("Channel Pipeline"), error.getMessage());
        assertFalse(error.getMessage().contains("\n"));
    }

    @Test
    void namesTheStatusWhenThereAreNoDetails() {
        server.fail("List", Status.INTERNAL);

        SQLException error = assertThrows(SQLException.class, gateway::listCollections);

        assertEquals("Qdrant: INTERNAL", error.getMessage());
    }

    @Test
    void timesOutSlowCalls() throws Exception {
        server.delay("List", Duration.ofSeconds(3));

        try (QdrantGateway impatient = open(null, Duration.ofMillis(300))) {
            SQLTimeoutException error = assertThrows(SQLTimeoutException.class,
                impatient::listCollections);

            assertEquals("HYT00", error.getSQLState());
        }
    }

    @Test
    void reportsAnUnreachableServerWithAHint() throws Exception {
        int port = server.port();
        server.close();

        try (QdrantGateway unreachable = QdrantGateway.open("localhost", port, false, null,
            Duration.ofSeconds(5), "test-agent/1.0")) {
            SQLNonTransientConnectionException error = assertThrows(
                SQLNonTransientConnectionException.class, unreachable::serverInfo);

            assertEquals("08001", error.getSQLState());
            assertTrue(error.getMessage().contains("localhost:" + port), error.getMessage());
            assertTrue(error.getMessage().contains("gRPC port"), error.getMessage());
        }
    }

    @Test
    void failsCallsAfterClose() throws SQLException {
        gateway.close();

        assertThrows(SQLException.class, gateway::listCollections);
        assertDoesNotThrow(gateway::close, "closing twice is harmless");
    }

    @Test
    void reportsAnInterruptedWait() {
        SettableFuture<String> never = SettableFuture.create();
        Thread.currentThread().interrupt();

        SQLException error = assertThrows(SQLException.class, () -> gateway.await(never));

        assertEquals("HY008", error.getSQLState());
        assertTrue(Thread.interrupted(), "the interrupt flag is restored");
        assertTrue(never.isCancelled());
    }

    @Test
    void wrapsFailuresThatArentGrpcErrors() {
        SettableFuture<String> failed = SettableFuture.create();
        IllegalStateException cause = new IllegalStateException("broken");
        failed.setException(cause);

        SQLException error = assertThrows(SQLException.class, () -> gateway.await(failed));

        assertEquals("HY000", error.getSQLState());
        assertSame(cause, error.getCause());
    }

    @Test
    void returnsTheResultOfACompletedCall() throws SQLException {
        SettableFuture<String> done = SettableFuture.create();
        done.set("result");

        assertEquals("result", gateway.await(done));
    }

    @Test
    void namesItsAddressAndExposesTheSdk() {
        assertEquals("localhost:" + server.port(), gateway.address());
        assertNotNull(gateway.sdk());
    }

    private QdrantGateway open(String apiKey, Duration timeout) {
        return QdrantGateway.open("localhost", server.port(), false, apiKey, timeout,
            "test-agent/1.0");
    }

    private static io.qdrant.client.grpc.Collections.CollectionInfo plainCollection() {
        return FakeQdrantServer.unnamedVector(2, Distance.Dot, Map.of());
    }

    private static List<RetrievedPoint> points(int count) {
        List<RetrievedPoint> points = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            points.add(FakeQdrantServer.point(id(i), Map.of("n", value(i))));
        }

        return points;
    }

    private static List<Long> ids(ScrollPage page) {
        return page.points().stream().map(point -> point.id().asLong()).toList();
    }
}
