package com.runaxsoftware.qdrant.jdbc.support;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Collections.CollectionDescription;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.GetCollectionInfoRequest;
import io.qdrant.client.grpc.Collections.GetCollectionInfoResponse;
import io.qdrant.client.grpc.Collections.ListCollectionsRequest;
import io.qdrant.client.grpc.Collections.ListCollectionsResponse;
import io.qdrant.client.grpc.Collections.PayloadSchemaInfo;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.CollectionsGrpc;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.PointsGrpc;
import io.qdrant.client.grpc.QdrantGrpc;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckReply;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A stand-in for Qdrant's gRPC API, for unit tests: health check, listing and reading
 * collections, and scrolling points. Collections, failures, delays, and a required API key
 * are set per test; requests and headers are recorded for assertions. Like Qdrant, it answers
 * health checks without an API key.
 */
public final class FakeQdrantServer implements AutoCloseable {

    /** The version the server reports. */
    public static final String VERSION = "1.20.0";

    private static final Metadata.Key<String> API_KEY =
        Metadata.Key.of("api-key", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> USER_AGENT =
        Metadata.Key.of("user-agent", Metadata.ASCII_STRING_MARSHALLER);

    private final Map<String, Collections.CollectionInfo> collections = new LinkedHashMap<>();
    private final Map<String, List<RetrievedPoint>> points = new ConcurrentHashMap<>();
    private final List<String> listedOnly = new CopyOnWriteArrayList<>();
    private final Map<String, Status> failures = new ConcurrentHashMap<>();
    private final Map<String, Duration> delays = new ConcurrentHashMap<>();
    private final Map<String, Integer> calls = new ConcurrentHashMap<>();
    private final List<ScrollPoints> scrollRequests = new CopyOnWriteArrayList<>();
    private final List<String> apiKeys = new CopyOnWriteArrayList<>();
    private final List<String> userAgents = new CopyOnWriteArrayList<>();
    private final Server server;
    private volatile String requiredApiKey;

    private FakeQdrantServer() throws IOException {
        server = ServerBuilder.forPort(0)
            .addService(new Health())
            .addService(new CollectionsService())
            .addService(new PointsService())
            .intercept(new Recorder())
            .build()
            .start();
    }

    /** Starts a server on a free port. */
    public static FakeQdrantServer start() throws IOException {
        return new FakeQdrantServer();
    }

    /** The port the server listens on. */
    public int port() {
        return server.getPort();
    }

    /** A JDBC URL for this server, with optional {@code ?...} parameters appended. */
    public String jdbcUrl(String... parameters) {
        return "jdbc:qdrant://localhost:" + port()
            + (parameters.length == 0 ? "" : "?" + String.join("&", parameters));
    }

    /** Adds a collection with its points; listed in the order added. */
    public synchronized FakeQdrantServer collection(String name, Collections.CollectionInfo info,
                                                    List<RetrievedPoint> collectionPoints) {
        collections.put(name, info);
        points.put(name, List.copyOf(collectionPoints));
        return this;
    }

    /** Lists a collection that can't be read, as if it was deleted after listing. */
    public FakeQdrantServer listedButMissing(String name) {
        listedOnly.add(name);
        return this;
    }

    /** Rejects calls (other than health checks) without this API key. */
    public FakeQdrantServer requireApiKey(String apiKey) {
        requiredApiKey = apiKey;
        return this;
    }

    /** Fails every call to a method ({@code HealthCheck}, {@code List}, {@code Get}, {@code Scroll}). */
    public FakeQdrantServer fail(String method, Status status) {
        failures.put(method, status);
        return this;
    }

    /** Stops failing a method. */
    public FakeQdrantServer recover(String method) {
        failures.remove(method);
        return this;
    }

    /** Delays every answer from a method. */
    public FakeQdrantServer delay(String method, Duration delay) {
        delays.put(method, delay);
        return this;
    }

    /** How many times a method was called. */
    public int calls(String method) {
        return calls.getOrDefault(method, 0);
    }

    /** The scroll requests received, in order. */
    public List<ScrollPoints> scrollRequests() {
        return List.copyOf(scrollRequests);
    }

    /** The {@code api-key} header of each call, or an empty string when there was none. */
    public List<String> apiKeys() {
        return List.copyOf(apiKeys);
    }

    /** The {@code user-agent} header of each call. */
    public List<String> userAgents() {
        return List.copyOf(userAgents);
    }

    @Override
    public void close() throws InterruptedException {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // Builders for test data

    /** A collection with one unnamed dense vector and the given payload indexes. */
    public static Collections.CollectionInfo unnamedVector(int size, Distance distance,
                                                           Map<String, PayloadSchemaType> indexes) {
        return collectionInfo(Collections.VectorsConfig.newBuilder()
            .setParams(VectorParams.newBuilder().setSize(size).setDistance(distance))
            .build(), Collections.SparseVectorConfig.getDefaultInstance(), indexes);
    }

    /** A collection with the given vectors configuration, sparse vectors, and payload indexes. */
    public static Collections.CollectionInfo collectionInfo(Collections.VectorsConfig vectors,
                                                            Collections.SparseVectorConfig sparse,
                                                            Map<String, PayloadSchemaType> indexes) {
        Collections.CollectionInfo.Builder info = Collections.CollectionInfo.newBuilder()
            .setPointsCount(42)
            .setConfig(Collections.CollectionConfig.newBuilder()
                .setParams(Collections.CollectionParams.newBuilder()
                    .setVectorsConfig(vectors)
                    .setSparseVectorsConfig(sparse)));

        for (Map.Entry<String, PayloadSchemaType> index : indexes.entrySet()) {
            info.putPayloadSchema(index.getKey(),
                PayloadSchemaInfo.newBuilder().setDataType(index.getValue()).build());
        }

        return info.build();
    }

    /** A point with a payload and no vectors. */
    public static RetrievedPoint point(PointId id, Map<String, Value> payload) {
        return RetrievedPoint.newBuilder().setId(id).putAllPayload(payload).build();
    }

    // Services

    private <T> void respond(String method, StreamObserver<T> observer, Supplier<T> response) {
        calls.merge(method, 1, Integer::sum);
        Duration delay = delays.get(method);

        if (delay != null) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Status failure = failures.get(method);

        if (failure != null) {
            observer.onError(failure.asRuntimeException());
            return;
        }

        try {
            observer.onNext(response.get());
            observer.onCompleted();
        } catch (StatusRuntimeException e) {
            observer.onError(e);
        }
    }

    private synchronized Collections.CollectionInfo find(String name) {
        Collections.CollectionInfo info = collections.get(name);

        if (info == null) {
            throw Status.NOT_FOUND
                .withDescription("Not found: Collection `" + name + "` doesn't exist!")
                .asRuntimeException();
        }

        return info;
    }

    private synchronized List<String> names() {
        List<String> names = new ArrayList<>(collections.keySet());
        names.addAll(listedOnly);
        return names;
    }

    private ScrollResponse scroll(ScrollPoints request) {
        find(request.getCollectionName());
        List<RetrievedPoint> all = points.get(request.getCollectionName());
        int start = 0;

        if (request.hasOffset()) {
            while (start < all.size() && !all.get(start).getId().equals(request.getOffset())) {
                start++;
            }
        }

        int limit = request.getLimit() == 0 ? 10 : request.getLimit();
        int end = Math.min(all.size(), start + limit);
        ScrollResponse.Builder response = ScrollResponse.newBuilder()
            .addAllResult(all.subList(start, end));

        if (end < all.size()) {
            response.setNextPageOffset(all.get(end).getId());
        }

        return response.build();
    }

    private final class Health extends QdrantGrpc.QdrantImplBase {
        @Override
        public void healthCheck(HealthCheckRequest request,
                                StreamObserver<HealthCheckReply> observer) {
            respond("HealthCheck", observer, () -> HealthCheckReply.newBuilder()
                .setTitle("qdrant - vector search engine")
                .setVersion(VERSION)
                .build());
        }
    }

    private final class CollectionsService extends CollectionsGrpc.CollectionsImplBase {
        @Override
        public void list(ListCollectionsRequest request,
                         StreamObserver<ListCollectionsResponse> observer) {
            respond("List", observer, () -> {
                ListCollectionsResponse.Builder response = ListCollectionsResponse.newBuilder();

                for (String name : names()) {
                    response.addCollections(CollectionDescription.newBuilder().setName(name));
                }

                return response.build();
            });
        }

        @Override
        public void get(GetCollectionInfoRequest request,
                        StreamObserver<GetCollectionInfoResponse> observer) {
            respond("Get", observer, () -> GetCollectionInfoResponse.newBuilder()
                .setResult(find(request.getCollectionName()))
                .build());
        }
    }

    private final class PointsService extends PointsGrpc.PointsImplBase {
        @Override
        public void scroll(ScrollPoints request, StreamObserver<ScrollResponse> observer) {
            scrollRequests.add(request);
            respond("Scroll", observer, () -> FakeQdrantServer.this.scroll(request));
        }
    }

    /** Records headers and enforces the API key, except for health checks. */
    private final class Recorder implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                         ServerCallHandler<Q, R> next) {
            String apiKey = headers.get(API_KEY);
            apiKeys.add(apiKey == null ? "" : apiKey);
            userAgents.add(String.valueOf(headers.get(USER_AGENT)));
            String required = requiredApiKey;
            boolean healthCheck = call.getMethodDescriptor().getBareMethodName().equals("HealthCheck");

            if (required != null && !healthCheck && !required.equals(apiKey)) {
                call.close(Status.UNAUTHENTICATED.withDescription(apiKey == null
                    ? "Must provide an API key or an Authorization bearer token"
                    : "Invalid API key or JWT"), new Metadata());
                return new ServerCall.Listener<>() {
                };
            }

            return next.startCall(call, headers);
        }
    }
}
