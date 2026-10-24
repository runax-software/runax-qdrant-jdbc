package com.runaxsoftware.qdrant.jdbc.client;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckReply;

import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * The driver's way into Qdrant, through the official Java SDK over gRPC.
 *
 * <p>Calls block until Qdrant answers and return the driver's own model types. Failures are
 * reported as {@link SQLException}s, so JDBC methods can pass them straight on: an unreachable
 * server as {@link SQLNonTransientConnectionException}, a missing or wrong API key as
 * {@link SQLInvalidAuthorizationSpecException}, and a slow server as
 * {@link SQLTimeoutException}. Other errors carry Qdrant's message and the gRPC status code as
 * the vendor code. The API key never appears in messages.
 */
public final class QdrantGateway implements AutoCloseable {

    /**
     * Qdrant's default gRPC port.
     */
    public static final int DEFAULT_PORT = 6334;

    /**
     * Large enough for a page of points with big vectors; gRPC's default is 4 MB.
     */
    private static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;

    private final QdrantClient client;
    private final String address;

    private QdrantGateway(QdrantClient client, String address) {
        this.client = client;
        this.address = address;
    }

    /**
     * Opens a channel to Qdrant. Nothing is sent until the first call.
     *
     * @param host      the server's host name or address
     * @param port      the server's gRPC port, usually {@value #DEFAULT_PORT}
     * @param tls       whether to use TLS
     * @param apiKey    the API key, or {@code null} when the server doesn't need one
     * @param timeout   the deadline for each call
     * @param userAgent the user agent to report
     */
    public static QdrantGateway open(String host, int port, boolean tls, String apiKey,
                                     Duration timeout, String userAgent) {
        ManagedChannelBuilder<?> channel = ManagedChannelBuilder.forAddress(host, port)
            .userAgent(userAgent)
            .maxInboundMessageSize(MAX_MESSAGE_BYTES);

        if (tls) {
            channel.useTransportSecurity();
        } else {
            channel.usePlaintext();
        }

        QdrantGrpcClient.Builder grpc = QdrantGrpcClient.newBuilder(channel.build(), true, false)
            .withTimeout(timeout);

        if (apiKey != null && !apiKey.isEmpty()) {
            grpc.withApiKey(apiKey);
        }

        return new QdrantGateway(new QdrantClient(grpc.build()), host + ":" + port);
    }

    /**
     * The server's {@code host:port}.
     */
    public String address() {
        return address;
    }

    /**
     * The SDK client, for operations the gateway doesn't wrap.
     */
    public QdrantClient sdk() {
        return client;
    }

    /**
     * Reads the server's name and version.
     */
    public ServerInfo serverInfo() throws SQLException {
        HealthCheckReply reply = await(client.healthCheckAsync());
        return new ServerInfo(reply.getTitle(), reply.getVersion());
    }

    /**
     * Lists the names of all collections. Needs a valid API key when the server has one.
     */
    public List<String> listCollections() throws SQLException {
        return await(client.listCollectionsAsync());
    }

    /**
     * Reads a collection's configuration, payload indexes, and point count.
     */
    public CollectionInfo collectionInfo(String collection) throws SQLException {
        Collections.CollectionInfo info = await(client.getCollectionInfoAsync(collection));
        return SdkModels.collectionInfo(collection, info);
    }

    /**
     * Reads one page of points, in point ID order.
     */
    public ScrollPage scroll(ScrollRequest request) throws SQLException {
        ScrollPoints.Builder scroll = ScrollPoints.newBuilder()
            .setCollectionName(request.collection())
            .setLimit(request.limit())
            .setWithPayload(WithPayloadSelectorFactory.enable(request.withPayload()))
            .setWithVectors(WithVectorsSelectorFactory.enable(request.withVector()));

        if (request.offset() != null) {
            scroll.setOffset(SdkModels.pointId(request.offset()));
        }

        ScrollResponse response = await(client.scrollAsync(scroll.build()));
        List<Point> points = new ArrayList<>();

        for (RetrievedPoint point : response.getResultList()) {
            points.add(SdkModels.point(point));
        }

        return new ScrollPage(List.copyOf(points),
            response.hasNextPageOffset() ? SdkModels.pointId(response.getNextPageOffset()) : null);
    }

    /**
     * Waits for an SDK call and turns its failure into an {@link SQLException}. Public so code
     * that calls the SDK directly, through {@link #sdk()}, reports errors the same way.
     */
    public <T> T await(ListenableFuture<T> call) throws SQLException {
        try {
            return call.get();
        } catch (InterruptedException e) {
            call.cancel(true);

            Thread.currentThread().interrupt();

            throw new SQLException("Interrupted while waiting for Qdrant", "HY008", e);
        } catch (ExecutionException e) {
            throw translate(e.getCause() == null ? e : e.getCause());
        }
    }

    /**
     * Closes the channel.
     */
    @Override
    public void close() {
        client.close();
    }

    private SQLException translate(Throwable failure) {
        Status status = statusOf(failure);
        if (status == null) {
            return new SQLException("Qdrant call failed: " + failure, "HY000", failure);
        }

        int code = status.getCode().value();
        // gRPC sometimes appends internals, such as Netty's channel pipeline, on later lines.
        String detail = status.getDescription() == null
            ? status.getCode().toString()
            : status.getDescription().lines().findFirst().orElse("").strip();

        return switch (status.getCode()) {
            case UNAVAILABLE -> new SQLNonTransientConnectionException("Can't reach Qdrant at " + address
                + ": " + detail + ". Check the host and that the port is Qdrant's gRPC port ("
                + DEFAULT_PORT + " by default)", "08001", code, failure);
            case UNAUTHENTICATED, PERMISSION_DENIED -> new SQLInvalidAuthorizationSpecException(
                "Qdrant rejected the API key (put it in the password field): " + detail,
                "28000", code, failure);
            case DEADLINE_EXCEEDED -> new SQLTimeoutException("Qdrant at " + address + " didn't answer in time",
                "HYT00", code, failure);
            case CANCELLED -> new SQLException("The Qdrant call was cancelled", "HY008", code, failure);
            case NOT_FOUND -> new SQLException("Qdrant: " + detail, "42S02", code, failure);
            case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE ->
                new SQLException("Qdrant: " + detail, "42000", code, failure);
            default -> new SQLException("Qdrant: " + detail, "HY000", code, failure);
        };
    }

    private static Status statusOf(Throwable failure) {
        if (failure instanceof StatusRuntimeException) {
            return ((StatusRuntimeException) failure).getStatus();
        }

        if (failure instanceof StatusException) {
            return ((StatusException) failure).getStatus();
        }

        return null;
    }
}
