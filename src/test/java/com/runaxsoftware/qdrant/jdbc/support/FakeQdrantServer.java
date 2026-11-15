package com.runaxsoftware.qdrant.jdbc.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A stand-in for Qdrant's REST API, for unit tests: the version at {@code GET /}, listing and
 * reading collections, listing aliases, scrolling points, searching them, counting them, and
 * the endpoints the system tables read — snapshots, shard keys, cluster info, telemetry,
 * metrics, and issues. Like Qdrant, it reads, scrolls, searches, and counts a collection by an
 * alias's name too. Answers have the shapes Qdrant 1.20 sends, including the Prometheus text
 * format at {@code GET /metrics}.
 * Collections, failures, delays, and a required API key are set per test; requests are recorded
 * for assertions. Like Qdrant, it answers {@code GET /} without an API key, answers a missing
 * or wrong key with plain text, and answers routes it doesn't have (such as {@code POST /})
 * with an empty 404.
 */
public final class FakeQdrantServer implements AutoCloseable {

    /**
     * The version the server reports.
     */
    public static final String VERSION = "1.20.0";

    /**
     * The git commit the server reports.
     */
    public static final String COMMIT = "222b2e44ab1c3f17e8246396ead6558e45041df7";

    /**
     * The id of the peer the server answers as, as Qdrant reports it in its cluster answers.
     */
    public static final long PEER_ID = 2121641890029304L;

    /**
     * The metrics the server answers with unless a test sets others: a gauge, a labelled gauge,
     * a counter, and a histogram, in Prometheus' text format as Qdrant sends it.
     */
    public static final String METRICS = """
        # HELP app_info information about qdrant server
        # TYPE app_info gauge
        app_info{name="qdrant",version="1.20.0"} 1
        # HELP collection_points approximate amount of points per collection
        # TYPE collection_points gauge
        collection_points{id="products"} 42
        collection_points{id="articles"} 7
        # HELP rest_responses_total total number of responses through the rest API
        # TYPE rest_responses_total counter
        rest_responses_total{method="GET",endpoint="/collections",status="200"} 4
        # HELP rest_responses_duration_seconds duration of responses through the rest API
        # TYPE rest_responses_duration_seconds histogram
        rest_responses_duration_seconds_bucket{method="GET",endpoint="/collections",le="0.001"} 3
        rest_responses_duration_seconds_bucket{method="GET",endpoint="/collections",le="+Inf"} 4
        rest_responses_duration_seconds_sum{method="GET",endpoint="/collections"} 0.0785
        rest_responses_duration_seconds_count{method="GET",endpoint="/collections"} 4
        """;

    /**
     * The endpoints the fake serves, for failing, delaying, and counting calls.
     */
    public enum Endpoint {
        /**
         * {@code GET /}: the version, which needs no API key.
         */
        VERSION,

        /**
         * {@code GET /collections}.
         */
        COLLECTIONS,

        /**
         * {@code GET /collections/{name}}.
         */
        COLLECTION,

        /**
         * {@code POST /collections/{name}/points/scroll}.
         */
        SCROLL,

        /**
         * {@code GET /collections/{name}/points/{id}}: one point by its ID.
         */
        POINT,

        /**
         * {@code POST /collections/{name}/points/query}.
         */
        QUERY,

        /**
         * {@code POST /collections/{name}/points/query/groups}: the best points of each group.
         */
        QUERY_GROUPS,

        /**
         * {@code POST /collections/{name}/points/search/matrix/pairs}.
         */
        MATRIX_PAIRS,

        /**
         * {@code POST /collections/{name}/points/search/matrix/offsets}.
         */
        MATRIX_OFFSETS,

        /**
         * {@code POST /collections/{name}/points/query/batch}: several searches in one call.
         */
        QUERY_BATCH,

        /**
         * {@code POST /collections/{name}/points/count}.
         */
        COUNT,

        /**
         * {@code POST /collections/{name}/facet}.
         */
        FACET,

        /**
         * {@code GET /aliases}.
         */
        ALIASES,

        /**
         * {@code GET /snapshots}: the whole storage's snapshots.
         */
        SNAPSHOTS,

        /**
         * {@code GET /collections/{name}/snapshots}.
         */
        COLLECTION_SNAPSHOTS,

        /**
         * {@code GET /collections/{name}/shards/{id}/snapshots}: one shard's snapshots.
         */
        SHARD_SNAPSHOTS,

        /**
         * {@code GET /collections/{name}/shards}: the collection's shard keys.
         */
        SHARD_KEYS,

        /**
         * {@code GET /collections/{name}/optimizations}.
         */
        OPTIMIZATIONS,

        /**
         * {@code GET /collections/{name}/cluster}: the collection's shards.
         */
        COLLECTION_CLUSTER,

        /**
         * {@code GET /cluster}.
         */
        CLUSTER,

        /**
         * {@code GET /telemetry}.
         */
        TELEMETRY,

        /**
         * {@code GET /metrics}, which answers in Prometheus' text format rather than JSON.
         */
        METRICS,

        /**
         * {@code GET /issues}.
         */
        ISSUES,

        /**
         * {@code DELETE /issues}, which forgets every issue reported so far.
         */
        CLEAR_ISSUES,

        /**
         * {@code GET /cluster/telemetry}, which only a cluster answers.
         */
        CLUSTER_TELEMETRY,

        /**
         * {@code GET /quotas}.
         */
        QUOTAS,

        /**
         * {@code PUT /quotas}, which replaces the quota config.
         */
        SET_QUOTAS,

        /**
         * {@code GET /healthz}, which answers in text and needs no API key.
         */
        HEALTHZ,

        /**
         * {@code GET /livez}, the same check as {@code /healthz}.
         */
        LIVEZ,

        /**
         * {@code GET /readyz}, which answers 503 while a shard isn't ready.
         */
        READYZ
    }

    /**
     * One request the server received.
     *
     * @param method    the HTTP method
     * @param path      the decoded path, such as {@code /collections/my docs}
     * @param apiKey    the {@code api-key} header, or {@code null} when there was none
     * @param userAgent the {@code User-Agent} header
     * @param body      the JSON body, or {@code null} when there was none
     * @param query     the query string, decoded, or {@code null} when there was none
     */
    public record Request(String method, String path, String apiKey, String userAgent,
                          JsonNode body, String query) {
    }

    // The endpoints Qdrant answers without an API key
    private static final Set<Endpoint> OPEN = Set.of(Endpoint.VERSION, Endpoint.HEALTHZ,
        Endpoint.LIVEZ, Endpoint.READYZ);

    private record Failure(int status, String contentType, String body) {
    }

    private final Map<String, ObjectNode> collections = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final Map<String, List<ObjectNode>> points = new ConcurrentHashMap<>();
    private final List<ObjectNode> storageSnapshots = new CopyOnWriteArrayList<>();
    private final Map<String, List<ObjectNode>> collectionSnapshots = new ConcurrentHashMap<>();
    private final Map<String, List<ObjectNode>> shardSnapshots = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> shardKeys = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> optimizations = new ConcurrentHashMap<>();
    private volatile JsonNode quotas = Json.object("enabled", false);
    private final Map<String, ObjectNode> collectionClusters = new ConcurrentHashMap<>();
    private final List<ObjectNode> issues = new CopyOnWriteArrayList<>();
    private final List<String> listedOnly = new CopyOnWriteArrayList<>();
    private final Map<Endpoint, Failure> failures = new ConcurrentHashMap<>();
    private final Map<Endpoint, Duration> delays = new ConcurrentHashMap<>();
    private final Map<Endpoint, Integer> calls = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final HttpServer server;
    private volatile String requiredApiKey;
    private volatile ObjectNode cluster = Json.object("status", "disabled");
    private volatile ObjectNode telemetry = telemetry(0);
    private volatile String metrics = METRICS;

    private FakeQdrantServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(threads);
        server.start();
    }

    /**
     * Starts a server on a free port.
     */
    public static FakeQdrantServer start() throws IOException {
        return new FakeQdrantServer();
    }

    /**
     * The port the server listens on.
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * A JDBC URL for this server, with optional {@code ?...} parameters appended.
     */
    public String jdbcUrl(String... parameters) {
        return "jdbc:qdrant://localhost:" + port()
            + (parameters.length == 0 ? "" : "?" + String.join("&", parameters));
    }

    /**
     * Adds a collection with its points; collections are listed in the order added.
     *
     * @param info   the collection as {@code GET /collections/{name}} returns it in
     *               {@code result}; see {@link #collectionInfo}
     * @param points the points, in scroll order; see {@link #point}
     */
    public synchronized FakeQdrantServer collection(String name, ObjectNode info,
                                                    List<ObjectNode> points) {
        collections.put(name, info);
        this.points.put(name, List.copyOf(points));
        return this;
    }

    /**
     * Deletes a collection, as if someone deleted it in Qdrant, which deletes its aliases too.
     */
    public synchronized FakeQdrantServer deleteCollection(String name) {
        collections.remove(name);
        points.remove(name);
        aliases.values().removeIf(name::equals);
        return this;
    }

    /**
     * Points an alias at a collection, creating the alias or moving it as Qdrant does in one
     * atomic change; aliases are listed in the order created.
     */
    public synchronized FakeQdrantServer alias(String alias, String collection) {
        aliases.put(alias, collection);
        return this;
    }

    /**
     * Deletes an alias, leaving its collection.
     */
    public synchronized FakeQdrantServer deleteAlias(String alias) {
        aliases.remove(alias);
        return this;
    }

    /**
     * Adds a snapshot, listed in the order added.
     *
     * @param collection  the collection it is of, or {@code null} for a whole-storage snapshot
     * @param description the snapshot as Qdrant describes it; see {@link #snapshotDescription}
     */
    public FakeQdrantServer snapshot(String collection, ObjectNode description) {
        if (collection == null) storageSnapshots.add(description);
        else collectionSnapshots.computeIfAbsent(collection,
            name -> new CopyOnWriteArrayList<>()).add(description);

        return this;
    }

    /**
     * Adds a snapshot of one shard, listed in the order added.
     *
     * @param description the snapshot as Qdrant describes it; see {@link #snapshotDescription}
     */
    public FakeQdrantServer shardSnapshot(String collection, long shard, ObjectNode description) {
        shardSnapshots.computeIfAbsent(collection + "/" + shard,
            name -> new CopyOnWriteArrayList<>()).add(description);

        return this;
    }

    /**
     * Gives a collection shard keys, which Qdrant lists in the order they were created.
     *
     * @param keys strings or whole numbers, the two kinds Qdrant takes
     */
    public FakeQdrantServer shardKeys(String collection, Object... keys) {
        List<JsonNode> list = new ArrayList<>();
        for (Object key : keys) list.add(Json.node(key));
        shardKeys.put(collection, list);

        return this;
    }

    /**
     * Sets a collection's shards, as {@code GET /collections/{name}/cluster} describes them;
     * see {@link #collectionCluster(List, List)}. A collection without any set is answered with
     * one local shard, as a standalone server answers for a collection it holds.
     */
    public FakeQdrantServer collectionCluster(String collection, ObjectNode info) {
        collectionClusters.put(collection, info);
        return this;
    }

    /**
     * Sets what {@code GET /cluster} answers; see {@link #enabledCluster}. Unless a test sets
     * one, the server answers as a standalone Qdrant does: a status of {@code disabled} alone.
     */
    public FakeQdrantServer cluster(ObjectNode cluster) {
        this.cluster = cluster;
        return this;
    }

    /**
     * Sets what {@code GET /telemetry} answers; see {@link #telemetry(int)}.
     */
    public FakeQdrantServer telemetry(ObjectNode telemetry) {
        this.telemetry = telemetry;
        return this;
    }

    /**
     * Sets what {@code GET /metrics} answers, in Prometheus' text format. {@link #METRICS} is
     * the default.
     */
    public FakeQdrantServer metrics(String metrics) {
        this.metrics = metrics;
        return this;
    }

    /**
     * Adds an issue to the ones {@code GET /issues} reports; see {@link #issue(String, String,
     * String)}.
     */
    public FakeQdrantServer issue(ObjectNode issue) {
        issues.add(issue);
        return this;
    }

    /**
     * Lists a collection that can't be read, as if it was deleted after listing.
     */
    public FakeQdrantServer listedButMissing(String name) {
        listedOnly.add(name);
        return this;
    }

    /**
     * Rejects requests (other than {@code GET /}) without this API key.
     */
    public FakeQdrantServer requireApiKey(String apiKey) {
        requiredApiKey = apiKey;
        return this;
    }

    /**
     * Answers every request to an endpoint with an error in Qdrant's JSON shape,
     * {@code {"status": {"error": message}}}.
     */
    public FakeQdrantServer fail(Endpoint endpoint, int status, String message) {
        failures.put(endpoint, new Failure(status, "application/json",
            Json.object("status", Json.object("error", message), "time", 0.0).toString()));
        return this;
    }

    /**
     * Answers every request to an endpoint with this status and body, sent as they are.
     */
    public FakeQdrantServer failWithBody(Endpoint endpoint, int status, String contentType,
                                         String body) {
        failures.put(endpoint, new Failure(status, contentType, body));
        return this;
    }

    /**
     * Stops failing an endpoint.
     */
    public FakeQdrantServer recover(Endpoint endpoint) {
        failures.remove(endpoint);
        return this;
    }

    /**
     * Delays every answer from an endpoint.
     */
    public FakeQdrantServer delay(Endpoint endpoint, Duration delay) {
        delays.put(endpoint, delay);
        return this;
    }

    /**
     * How many requests reached an endpoint, including rejected ones.
     */
    public int calls(Endpoint endpoint) {
        return calls.getOrDefault(endpoint, 0);
    }

    /**
     * Every request received, in order.
     */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /**
     * The bodies of the scroll requests received, in order.
     */
    public List<JsonNode> scrollRequests() {
        return requests.stream()
            .filter(request -> request.path().endsWith("/points/scroll"))
            .map(Request::body)
            .toList();
    }

    /**
     * The bodies of the searches received, in order.
     */
    public List<JsonNode> queryRequests() {
        return requests.stream()
            .filter(request -> request.path().endsWith("/points/query"))
            .map(Request::body)
            .toList();
    }

    /**
     * The bodies of the counts received, in order.
     */
    public List<JsonNode> countRequests() {
        return requests.stream()
            .filter(request -> request.path().endsWith("/points/count"))
            .map(Request::body)
            .toList();
    }

    /**
     * The {@code api-key} header of each request, or an empty string when there was none.
     */
    public List<String> apiKeys() {
        return requests.stream()
            .map(request -> request.apiKey() == null ? "" : request.apiKey())
            .toList();
    }

    /**
     * The {@code User-Agent} header of each request.
     */
    public List<String> userAgents() {
        return requests.stream().map(Request::userAgent).toList();
    }

    @Override
    public void close() {
        server.stop(0);
        threads.shutdownNow();
    }

    // Builders for test data

    /**
     * A collection with one unnamed dense vector and the given payload indexes.
     *
     * @param indexes payload path to Qdrant's index type name, such as {@code "keyword"}
     */
    public static ObjectNode unnamedVector(int size, String distance, Map<String, String> indexes) {
        return collectionInfo(dense(size, distance), null, indexes);
    }

    /**
     * A collection as {@code GET /collections/{name}} describes it, with 42 points.
     *
     * @param vectors       one vector's parameters ({@link #dense}) or named vectors' parameters
     * @param sparseVectors the sparse vectors by name, or {@code null} for none
     * @param indexes       payload path to Qdrant's index type name, such as {@code "keyword"}
     */
    public static ObjectNode collectionInfo(ObjectNode vectors, ObjectNode sparseVectors,
                                            Map<String, String> indexes) {
        ObjectNode params = Json.object("vectors", vectors, "shard_number", 1,
            "replication_factor", 1, "on_disk_payload", true);

        if (sparseVectors != null) {
            params.set("sparse_vectors", sparseVectors);
        }

        ObjectNode schema = Json.object();
        for (Map.Entry<String, String> index : indexes.entrySet()) {
            schema.set(index.getKey(), Json.object("data_type", index.getValue(), "points", 42));
        }

        return Json.object("status", "green", "optimizer_status", "ok", "points_count", 42,
            "segments_count", 1, "config", Json.object("params", params), "payload_schema", schema);
    }

    /**
     * A dense vector's parameters.
     */
    public static ObjectNode dense(int size, String distance) {
        return Json.object("size", size, "distance", distance);
    }

    /**
     * A multivector's parameters.
     */
    public static ObjectNode multivector(int size, String distance) {
        return dense(size, distance).set("multivector_config",
            Json.object("comparator", "max_sim"));
    }

    /**
     * A snapshot as Qdrant describes it, of a collection or of the whole storage.
     *
     * @param creationTime Qdrant's own format, which carries no zone, such as
     *                     {@code 2026-11-01T17:05:18}
     */
    public static ObjectNode snapshotDescription(String name, String creationTime, long size,
                                                 String checksum) {
        return Json.object("name", name, "creation_time", creationTime, "size", size,
            "checksum", checksum);
    }

    /**
     * A collection's shards, as {@code GET /collections/{name}/cluster} describes them, held by
     * the peer that answers.
     *
     * @param local  shards on this peer; see {@link #localShard}
     * @param remote shards on other peers; see {@link #remoteShard}
     */
    public static ObjectNode collectionCluster(List<ObjectNode> local, List<ObjectNode> remote) {
        return Json.object("peer_id", PEER_ID, "shard_count", local.size() + remote.size(),
            "local_shards", Json.array(local.toArray()),
            "remote_shards", Json.array(remote.toArray()),
            "shard_transfers", Json.array());
    }

    /**
     * A shard on the peer that answers, which counts its points.
     *
     * @param shardKey a string, a whole number, or {@code null} when the collection isn't
     *                 custom-sharded
     */
    public static ObjectNode localShard(int shardId, Object shardKey, long points, String state) {
        ObjectNode shard = Json.object("shard_id", shardId, "points_count", points,
            "state", state);

        return shardKey == null ? shard : shard.set("shard_key", Json.node(shardKey));
    }

    /**
     * A shard on another peer, whose points Qdrant doesn't count.
     */
    public static ObjectNode remoteShard(int shardId, Object shardKey, long peerId, String state) {
        ObjectNode shard = Json.object("shard_id", shardId, "peer_id", peerId, "state", state);

        return shardKey == null ? shard : shard.set("shard_key", Json.node(shardKey));
    }

    /**
     * A cluster as {@code GET /cluster} describes it to a peer of it, this server's peer
     * leading the term.
     *
     * @param peers peer id to internal address
     */
    public static ObjectNode enabledCluster(Map<Long, String> peers) {
        return Json.object("status", "enabled", "peer_id", PEER_ID,
            "peers", mapped(peers),
            "raft_info", Json.object("term", 1, "commit", 6, "pending_operations", 0,
                "leader", PEER_ID, "role", "Leader", "is_voter", true),
            "consensus_thread_status", Json.object("consensus_thread_status", "working",
                "last_update", "2026-11-01T17:06:39.195643502Z"),
            "message_send_failures", Json.object());
    }

    /**
     * Telemetry in the shape Qdrant answers {@code GET /telemetry} with by default: a tree of
     * a few scalars, deep enough for the paths the driver builds.
     */
    public static ObjectNode telemetry(int collections) {
        return Json.object(
            "id", "fbada747-2337-4774-9831-2089bbb0d5e7",
            "app", Json.object("name", "qdrant", "version", VERSION, "startup",
                "2026-10-24T14:06:02.030Z"),
            "collections", Json.object("number_of_collections", collections),
            "cluster", Json.object("enabled", false, "resharding_enabled", false),
            "requests", Json.object("rest", Json.object("responses", Json.object(
                "GET /collections", Json.object("200", Json.object("count", 4,
                    "avg_duration_micros", 24.5))))));
    }

    /**
     * An issue in the shape Qdrant reports one, with the solution it suggests.
     */
    public static ObjectNode issue(String id, String description, String collection) {
        return Json.object("id", id, "description", description,
            "solution", Json.object("immediate", Json.object("message", "Create an index",
                "action", Json.object("method", "PUT", "uri", "/collections/x/index"))),
            "timestamp", "2026-11-01T17:10:01.997331971Z",
            "related_collection", collection);
    }

    private static ObjectNode mapped(Map<Long, String> peers) {
        ObjectNode listed = Json.object();
        peers.forEach((id, uri) -> listed.set(String.valueOf(id), Json.object("uri", uri)));

        return listed;
    }

    /**
     * A point with a payload and no vectors.
     *
     * @param id a number or a UUID string
     */
    public static ObjectNode point(Object id, ObjectNode payload) {
        return Json.object("id", id, "payload", payload);
    }

    /**
     * A point with a payload and vectors.
     *
     * @param id     a number or a UUID string
     * @param vector the unnamed vector, or an object of named vectors
     */
    public static ObjectNode point(Object id, ObjectNode payload, JsonNode vector) {
        return point(id, payload).set("vector", vector);
    }

    // Serving

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(),
                StandardCharsets.UTF_8);
            String apiKey = exchange.getRequestHeaders().getFirst("api-key");
            JsonNode body = readBody(exchange.getRequestBody());
            requests.add(new Request(method, path, apiKey,
                exchange.getRequestHeaders().getFirst("User-Agent"), body,
                exchange.getRequestURI().getQuery()));

            String[] segments = path.split("/", -1);
            Endpoint endpoint = route(method, segments);

            if (endpoint == null) {
                send(exchange, 404, "text/plain", "");
                return;
            }

            calls.merge(endpoint, 1, Integer::sum);
            pause(endpoint);

            String required = requiredApiKey;
            if (!OPEN.contains(endpoint) && required != null && !required.equals(apiKey)) {
                send(exchange, 401, "text/plain", apiKey == null
                    ? "Must provide an API key or an Authorization bearer token"
                    : "Invalid API key or JWT");
                return;
            }

            Failure failure = failures.get(endpoint);
            if (failure != null) {
                send(exchange, failure.status(), failure.contentType(), failure.body());
                return;
            }

            switch (endpoint) {
                case VERSION -> send(exchange, 200, "application/json", Json.object(
                    "title", "qdrant - vector search engine", "version", VERSION,
                    "commit", COMMIT).toString());
                case COLLECTIONS -> ok(exchange, Json.object("collections", names()));
                case COLLECTION -> collectionInfo(exchange, segments[2]);
                case SCROLL -> scroll(exchange, segments[2], body);
                case POINT -> point(exchange, segments[2], segments[4]);
                case QUERY -> query(exchange, segments[2], body);
                case QUERY_BATCH -> queryBatch(exchange, segments[2], body);
                case QUERY_GROUPS -> queryGroups(exchange, segments[2], body);
                case MATRIX_PAIRS -> matrix(exchange, segments[2], body, false);
                case MATRIX_OFFSETS -> matrix(exchange, segments[2], body, true);
                case COUNT -> count(exchange, segments[2]);
                case FACET -> facet(exchange, segments[2], body);
                case ALIASES -> ok(exchange, Json.object("aliases", aliasList()));
                case SNAPSHOTS -> ok(exchange, Json.array(storageSnapshots.toArray()));
                case COLLECTION_SNAPSHOTS -> collectionSnapshots(exchange, segments[2]);
                case SHARD_SNAPSHOTS -> shardSnapshots(exchange, segments[2], segments[4]);
                case SHARD_KEYS -> shardKeys(exchange, segments[2]);
                case OPTIMIZATIONS -> optimizations(exchange, segments[2]);
                case COLLECTION_CLUSTER -> collectionCluster(exchange, segments[2]);
                case CLUSTER -> ok(exchange, cluster);
                case CLUSTER_TELEMETRY -> {
                    if (cluster.path("status").asText().equals("enabled")) {
                        ok(exchange, Json.object("cluster", Json.object("enabled", true,
                            "number_of_peers", cluster.path("peers").size())));
                    } else {
                        send(exchange, 500, "application/json", Json.object("status",
                            Json.object("error", "Service internal error: Could not get "
                                + "telemetry from cluster")).toString());
                    }
                }
                case TELEMETRY -> ok(exchange, telemetry);
                case ISSUES -> ok(exchange, Json.object("issues", Json.array(issues.toArray())));
                case QUOTAS -> ok(exchange, Json.object("config", quotas,
                    "usage", Json.object("resident_memory_percent", 3, "disk_usage_percent", 17)));
                case SET_QUOTAS -> {
                    quotas = body;
                    ok(exchange, BooleanNode.TRUE);
                }
                case CLEAR_ISSUES -> {
                    issues.clear();
                    ok(exchange, BooleanNode.TRUE);
                }
                case METRICS -> send(exchange, 200, "text/plain; charset=utf-8", metrics);
                case HEALTHZ, LIVEZ -> send(exchange, 200, "text/plain; charset=utf-8",
                    "healthz check passed");
                case READYZ -> send(exchange, 200, "text/plain; charset=utf-8",
                    "all shards are ready");
            }
        } finally {
            exchange.close();
        }
    }

    private static Endpoint route(String method, String[] segments) {
        // "/collections/x/points/scroll".split("/") is ["", "collections", "x", "points", "scroll"]
        if (method.equals("GET") && segments.length == 2 && segments[1].isEmpty()) {
            return Endpoint.VERSION;
        }

        if (method.equals("GET") && segments.length == 2) {
            switch (segments[1]) {
                case "aliases": return Endpoint.ALIASES;
                case "snapshots": return Endpoint.SNAPSHOTS;
                case "cluster": return Endpoint.CLUSTER;
                case "telemetry": return Endpoint.TELEMETRY;
                case "metrics": return Endpoint.METRICS;
                case "issues": return Endpoint.ISSUES;
                case "quotas": return Endpoint.QUOTAS;
                case "healthz": return Endpoint.HEALTHZ;
                case "livez": return Endpoint.LIVEZ;
                case "readyz": return Endpoint.READYZ;
                default: break;
            }
        }

        if (method.equals("GET") && segments.length == 3 && segments[1].equals("cluster")
            && segments[2].equals("telemetry")) {
            return Endpoint.CLUSTER_TELEMETRY;
        }

        if (method.equals("PUT") && segments.length == 2 && segments[1].equals("quotas")) {
            return Endpoint.SET_QUOTAS;
        }

        if (method.equals("DELETE") && segments.length == 2 && segments[1].equals("issues")) {
            return Endpoint.CLEAR_ISSUES;
        }

        if (segments.length < 2 || !segments[1].equals("collections")) {
            return null;
        }

        if (method.equals("GET") && segments.length == 2) {
            return Endpoint.COLLECTIONS;
        }

        if (method.equals("GET") && segments.length == 3) {
            return Endpoint.COLLECTION;
        }

        if (method.equals("GET") && segments.length == 5 && segments[3].equals("points")) {
            return Endpoint.POINT;
        }

        if (method.equals("POST") && segments.length == 5 && segments[3].equals("points")
            && segments[4].equals("scroll")) {
            return Endpoint.SCROLL;
        }

        if (method.equals("POST") && segments.length == 5 && segments[3].equals("points")
            && segments[4].equals("query")) {
            return Endpoint.QUERY;
        }

        if (method.equals("POST") && segments.length == 6 && segments[3].equals("points")
            && segments[4].equals("query") && segments[5].equals("batch")) {
            return Endpoint.QUERY_BATCH;
        }

        if (method.equals("POST") && segments.length == 7 && segments[3].equals("points")
            && segments[4].equals("search") && segments[5].equals("matrix")) {
            switch (segments[6]) {
                case "pairs": return Endpoint.MATRIX_PAIRS;
                case "offsets": return Endpoint.MATRIX_OFFSETS;
                default: break;
            }
        }

        if (method.equals("POST") && segments.length == 6 && segments[3].equals("points")
            && segments[4].equals("query") && segments[5].equals("groups")) {
            return Endpoint.QUERY_GROUPS;
        }

        if (method.equals("POST") && segments.length == 5 && segments[3].equals("points")
            && segments[4].equals("count")) {
            return Endpoint.COUNT;
        }

        if (method.equals("POST") && segments.length == 4 && segments[3].equals("facet")) {
            return Endpoint.FACET;
        }

        if (method.equals("GET") && segments.length == 6 && segments[3].equals("shards")
            && segments[5].equals("snapshots")) {
            return Endpoint.SHARD_SNAPSHOTS;
        }

        if (method.equals("GET") && segments.length == 4) {
            switch (segments[3]) {
                case "snapshots": return Endpoint.COLLECTION_SNAPSHOTS;
                case "shards": return Endpoint.SHARD_KEYS;
                case "optimizations": return Endpoint.OPTIMIZATIONS;
                case "cluster": return Endpoint.COLLECTION_CLUSTER;
                default: break;
            }
        }

        return null;
    }

    private synchronized List<JsonNode> names() {
        List<JsonNode> names = new ArrayList<>();

        for (String name : collections.keySet()) {
            names.add(Json.object("name", name));
        }

        for (String name : listedOnly) {
            names.add(Json.object("name", name));
        }

        return names;
    }

    private synchronized List<JsonNode> aliasList() {
        List<JsonNode> list = new ArrayList<>();

        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            list.add(Json.object("alias_name", alias.getKey(), "collection_name", alias.getValue()));
        }

        return list;
    }

    // A collection by its name or an alias's, as Qdrant resolves them
    private synchronized String resolve(String name) {
        return aliases.getOrDefault(name, name);
    }

    private synchronized ObjectNode find(String name) {
        return collections.get(resolve(name));
    }

    private void collectionInfo(HttpExchange exchange, String name) throws IOException {
        ObjectNode info = find(name);

        if (info == null) {
            notFound(exchange, name);
            return;
        }

        ok(exchange, info);
    }

    private void shardSnapshots(HttpExchange exchange, String name, String shard)
        throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        ok(exchange, Json.array(
            shardSnapshots.getOrDefault(resolve(name) + "/" + shard, List.of()).toArray()));
    }

    private void collectionSnapshots(HttpExchange exchange, String name) throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        ok(exchange, Json.array(
            collectionSnapshots.getOrDefault(resolve(name), List.of()).toArray()));
    }

    /**
     * Sets the quota config {@code GET /quotas} answers with, until a {@code PUT} replaces it.
     */
    public FakeQdrantServer quotas(ObjectNode config) {
        quotas = config;

        return this;
    }

    /**
     * The quota config as the latest {@code PUT /quotas}, or {@link #quotas(ObjectNode)}, left it.
     */
    public JsonNode quotas() {
        return quotas;
    }

    /**
     * Gives a collection optimizations, as the lists Qdrant answers with: {@code running},
     * {@code queued}, and {@code completed}, each an array.
     */
    public FakeQdrantServer optimizations(String collection, ObjectNode lists) {
        optimizations.put(collection, lists);

        return this;
    }

    /**
     * A collection's optimizations. Like Qdrant, the running ones are always answered and the
     * queued and completed ones only when {@code ?with=} names them.
     */
    private void optimizations(HttpExchange exchange, String name) throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        ObjectNode lists = optimizations.getOrDefault(resolve(name), Json.object());
        String query = exchange.getRequestURI().getQuery();
        List<String> with = query != null && query.startsWith("with=")
            ? List.of(query.substring("with=".length()).split(",")) : List.of();

        ObjectNode result = Json.object(
            "summary", Json.object("queued_optimizations", lists.path("queued").size(),
                "queued_segments", 0, "queued_points", 0, "idle_segments", 1),
            "running", lists.has("running") ? lists.get("running") : Json.array());

        for (String list : List.of("queued", "completed")) {
            if (with.contains(list)) {
                result.set(list, lists.has(list) ? lists.get(list) : Json.array());
            }
        }

        ok(exchange, result);
    }

    /**
     * A collection's shard keys. Like Qdrant, a collection that isn't custom-sharded is
     * answered with an empty object rather than an empty list of keys.
     */
    private void shardKeys(HttpExchange exchange, String name) throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        List<JsonNode> keys = shardKeys.get(resolve(name));
        if (keys == null) {
            ok(exchange, Json.object());
            return;
        }

        List<ObjectNode> listed = new ArrayList<>();
        for (JsonNode key : keys) listed.add(Json.object("key", key));

        ok(exchange, Json.object("shard_keys", Json.array(listed.toArray())));
    }

    /**
     * A collection's shards. A collection no test set shards for gets one local shard, as a
     * standalone server answers for a collection it holds.
     */
    private void collectionCluster(HttpExchange exchange, String name) throws IOException {
        ObjectNode info = find(name);

        if (info == null) {
            notFound(exchange, name);
            return;
        }

        ObjectNode set = collectionClusters.get(resolve(name));
        ok(exchange, set != null ? set : collectionCluster(
            List.of(localShard(0, null, info.path("points_count").asLong(0), "Active")),
            List.of()));
    }

    /**
     * A page of a collection's points. The {@code offset} is the point ID to start at, as
     * Qdrant's is, not a row count, and asking for one together with an {@code order_by} fails
     * the way Qdrant 1.20 fails it.
     */
    // The point with an ID, whole, or Qdrant's answer for one it doesn't have
    private void point(HttpExchange exchange, String name, String id) throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        for (ObjectNode point : points.get(resolve(name))) {
            if (point.get("id").asText().equals(id)) {
                ok(exchange, point);
                return;
            }
        }

        send(exchange, 404, "application/json", Json.object("status",
            Json.object("error", "Not found: Point with id " + id + " does not exists!"),
            "time", 0.0).toString());
    }

    private void scroll(HttpExchange exchange, String name, JsonNode request) throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        List<ObjectNode> all = points.get(resolve(name));
        JsonNode offset = request == null ? null : request.get("offset");
        int start = 0;

        if (offset != null && !offset.isNull() && request.has("order_by")) {
            badRequest(exchange, "Cannot use an `offset` when using `order_by`. The alternative "
                + "for paging is to use `order_by.start_from` and a filter to exclude the IDs "
                + "that you've already seen for the `order_by.start_from` value");
            return;
        }

        if (offset != null && !offset.isNull()) {
            while (start < all.size() && !sameId(all.get(start).get("id"), offset)) {
                start++;
            }
        }

        int limit = request == null ? 10 : request.path("limit").asInt(10);
        boolean withPayload = request != null && request.path("with_payload").asBoolean(false);
        boolean withVector = request != null && request.path("with_vector").asBoolean(false);
        int end = Math.min(all.size(), start + limit);
        List<JsonNode> page = new ArrayList<>();

        for (ObjectNode point : all.subList(start, end)) {
            ObjectNode returned = Json.object("id", point.get("id"));

            if (withPayload && point.has("payload")) {
                returned.set("payload", point.get("payload"));
            }

            if (withVector && point.has("vector")) {
                returned.set("vector", point.get("vector"));
            }

            page.add(returned);
        }

        ok(exchange, Json.object("points", page,
            "next_page_offset", end < all.size() ? all.get(end).get("id") : null));
    }

    /**
     * Answers a search with the collection's points in the order they were stored, scored from
     * 1.0 down in steps of 0.1, so a test knows which rows to expect without this ranking
     * anything itself. Qdrant's own ordering is its business, not the driver's. The
     * {@code offset} is a row count, as the Query API's is: it passes over that many of the
     * best-ranked points, which keep the scores they would have had, and the limit still counts
     * the points returned after it.
     */
    private void query(HttpExchange exchange, String name, JsonNode request) throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        ok(exchange, queried(name, request));
    }

    // Every search of a batch, answered as it would be alone, in the order asked
    private void queryBatch(HttpExchange exchange, String name, JsonNode request)
        throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        List<JsonNode> answers = new ArrayList<>();
        for (JsonNode search : request.path("searches")) answers.add(queried(name, search));

        ok(exchange, Json.array(answers.toArray()));
    }

    /**
     * The points grouped by a payload field, scored as {@link #queried} scores them: as many
     * groups as the request's limit, each with its first {@code group_size} points, and a point
     * without the field in none, as Qdrant leaves it out.
     */
    private void queryGroups(HttpExchange exchange, String name, JsonNode request)
        throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        String field = request.path("group_by").asText();
        int size = request.path("group_size").asInt(3);
        int limit = request.path("limit").asInt(10);
        Map<JsonNode, List<JsonNode>> groups = new LinkedHashMap<>();
        List<ObjectNode> all = points.get(resolve(name));

        for (int i = 0; i < all.size(); i++) {
            JsonNode key = all.get(i).path("payload").path(field);
            if (key.isMissingNode() || key.isNull()) continue;

            List<JsonNode> hits = groups.computeIfAbsent(key, x -> new ArrayList<>());
            if (hits.size() < size && (groups.size() <= limit)) {
                hits.add(Json.object("id", all.get(i).get("id"), "version", 0,
                    "score", Math.round((1.0 - i * 0.1) * 100) / 100.0,
                    "payload", all.get(i).path("payload")));
            }
        }

        List<JsonNode> listed = new ArrayList<>();
        for (Map.Entry<JsonNode, List<JsonNode>> group : groups.entrySet()) {
            if (listed.size() == limit) break;

            listed.add(Json.object("id", group.getKey(), "hits", Json.array(group.getValue().toArray())));
        }

        ok(exchange, Json.object("groups", Json.array(listed.toArray())));
    }

    /**
     * A distance matrix of the collection's first {@code sample} points: each with the one
     * after it as its only neighbour, scored 0.9, 0.8 and so on, as pairs or as offsets.
     */
    private void matrix(HttpExchange exchange, String name, JsonNode request, boolean offsets)
        throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        List<ObjectNode> all = points.get(resolve(name));
        int sample = Math.min(all.size(), request.path("sample").asInt(10));
        List<JsonNode> ids = new ArrayList<>();
        List<JsonNode> pairs = new ArrayList<>();
        List<Integer> rows = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        List<Double> scores = new ArrayList<>();

        for (int i = 0; i < sample; i++) ids.add(all.get(i).get("id"));

        for (int i = 0; i < sample && sample > 1; i++) {
            int neighbour = (i + 1) % sample;
            double score = Math.round((0.9 - i * 0.1) * 100) / 100.0;

            pairs.add(Json.object("a", ids.get(i), "b", ids.get(neighbour), "score", score));
            rows.add(i);
            cols.add(neighbour);
            scores.add(score);
        }

        ok(exchange, offsets
            ? Json.object("offsets_row", Json.array(rows.toArray()),
                "offsets_col", Json.array(cols.toArray()), "scores", Json.array(scores.toArray()),
                "ids", Json.array(ids.toArray()))
            : Json.object("pairs", Json.array(pairs.toArray())));
    }

    private ObjectNode queried(String name, JsonNode request) {
        List<ObjectNode> all = points.get(resolve(name));
        int limit = request == null ? 10 : request.path("limit").asInt(10);
        int offset = request == null ? 0 : request.path("offset").asInt(0);
        boolean withPayload = request != null && request.path("with_payload").asBoolean(false);
        boolean withVector = request != null && request.path("with_vector").asBoolean(false);
        List<JsonNode> scored = new ArrayList<>();
        int first = Math.min(all.size(), offset);

        for (ObjectNode point : all.subList(first, Math.min(all.size(), first + limit))) {
            ObjectNode returned = Json.object("id", point.get("id"), "version", 0,
                "score", Math.round((1.0 - (first + scored.size()) * 0.1) * 100) / 100.0);

            if (withPayload && point.has("payload")) {
                returned.set("payload", point.get("payload"));
            }

            if (withVector && point.has("vector")) {
                returned.set("vector", point.get("vector"));
            }

            scored.add(returned);
        }

        return Json.object("points", scored);
    }

    /**
     * Answers a count with how many points the collection holds, the number Qdrant answers for
     * an exact count of a whole collection.
     */
    private void count(HttpExchange exchange, String name) throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        ok(exchange, Json.object("count", points.get(resolve(name)).size()));
    }

    /**
     * Counts the values of a payload field the way Qdrant's facet API does: a point counts once
     * per value, so an array field counts under each of its elements, and the most common value
     * comes first, ties broken by the value itself. The request's limit cuts the list, as
     * Qdrant's does, and an {@code offset} is ignored, as Qdrant ignores a field its facet API
     * has no place for — which is why the driver skips groups itself. The filter is recorded but
     * not applied — which points a filter matches is Qdrant's own business, so a test asserts
     * the filter that was sent and leaves the matching to an integration test.
     */
    private void facet(HttpExchange exchange, String name, JsonNode request) throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        String key = request == null ? "" : request.path("key").asText("");
        int limit = request == null ? 10 : request.path("limit").asInt(10);
        Map<JsonNode, Long> counts = new LinkedHashMap<>();

        for (ObjectNode point : points.get(resolve(name))) {
            JsonNode value = PayloadPath.valueAt(point.get("payload"), key);
            if (value == null || value.isNull()) continue;

            for (JsonNode each : value.isArray() ? value : List.of(value)) {
                counts.merge(each, 1L, Long::sum);
            }
        }

        List<JsonNode> hits = counts.entrySet().stream()
            .sorted(Map.Entry.<JsonNode, Long>comparingByValue().reversed()
                .thenComparing(entry -> entry.getKey().asText()))
            .limit(limit)
            .map(entry -> (JsonNode) Json.object("value", entry.getKey(), "count", entry.getValue()))
            .toList();

        ok(exchange, Json.object("hits", hits));
    }

    /**
     * The bodies of the facets received, in order.
     */
    public List<JsonNode> facetRequests() {
        return requests.stream()
            .filter(request -> request.path().endsWith("/facet"))
            .map(Request::body)
            .toList();
    }

    // 1, 1.0 and a BigInteger 1 are the same point ID
    private static boolean sameId(JsonNode id, JsonNode offset) {
        if (id.isNumber() && offset.isNumber()) {
            return id.bigIntegerValue().equals(offset.bigIntegerValue());
        }

        return id.asText().equals(offset.asText());
    }

    private static void notFound(HttpExchange exchange, String name) throws IOException {
        send(exchange, 404, "application/json", Json.object("status",
            Json.object("error", "Not found: Collection `" + name + "` doesn't exist!"),
            "time", 0.0).toString());
    }

    /**
     * A request Qdrant won't run, answered the way it answers one: a 400 whose message says
     * what's wrong, prefixed as Qdrant prefixes it.
     */
    private static void badRequest(HttpExchange exchange, String error) throws IOException {
        send(exchange, 400, "application/json", Json.object("status",
            Json.object("error", "Wrong input: " + error), "time", 0.0).toString());
    }

    private static void ok(HttpExchange exchange, JsonNode result) throws IOException {
        send(exchange, 200, "application/json",
            Json.object("result", result, "status", "ok", "time", 0.0).toString());
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
        throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);

        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private void pause(Endpoint endpoint) {
        Duration delay = delays.get(endpoint);
        if (delay == null) {
            return;
        }

        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonNode readBody(InputStream in) throws IOException {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return text.isBlank() ? null : Json.parse(text);
    }
}
