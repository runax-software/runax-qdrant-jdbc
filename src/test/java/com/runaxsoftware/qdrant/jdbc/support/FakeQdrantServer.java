package com.runaxsoftware.qdrant.jdbc.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A stand-in for Qdrant's REST API, for unit tests: the version at {@code GET /}, listing and
 * reading collections, and scrolling points. Answers have the shapes Qdrant 1.20 sends.
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
        SCROLL
    }

    /**
     * One request the server received.
     *
     * @param method    the HTTP method
     * @param path      the decoded path, such as {@code /collections/my docs}
     * @param apiKey    the {@code api-key} header, or {@code null} when there was none
     * @param userAgent the {@code User-Agent} header
     * @param body      the JSON body, or {@code null} when there was none
     */
    public record Request(String method, String path, String apiKey, String userAgent,
                          JsonNode body) {
    }

    private record Failure(int status, String contentType, String body) {
    }

    private final Map<String, ObjectNode> collections = new LinkedHashMap<>();
    private final Map<String, List<ObjectNode>> points = new ConcurrentHashMap<>();
    private final List<String> listedOnly = new CopyOnWriteArrayList<>();
    private final Map<Endpoint, Failure> failures = new ConcurrentHashMap<>();
    private final Map<Endpoint, Duration> delays = new ConcurrentHashMap<>();
    private final Map<Endpoint, Integer> calls = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final ExecutorService threads = Executors.newCachedThreadPool();
    private final HttpServer server;
    private volatile String requiredApiKey;

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
                exchange.getRequestHeaders().getFirst("User-Agent"), body));

            String[] segments = path.split("/", -1);
            Endpoint endpoint = route(method, segments);

            if (endpoint == null) {
                send(exchange, 404, "text/plain", "");
                return;
            }

            calls.merge(endpoint, 1, Integer::sum);
            pause(endpoint);

            String required = requiredApiKey;
            if (endpoint != Endpoint.VERSION && required != null && !required.equals(apiKey)) {
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

        if (segments.length < 2 || !segments[1].equals("collections")) {
            return null;
        }

        if (method.equals("GET") && segments.length == 2) {
            return Endpoint.COLLECTIONS;
        }

        if (method.equals("GET") && segments.length == 3) {
            return Endpoint.COLLECTION;
        }

        if (method.equals("POST") && segments.length == 5 && segments[3].equals("points")
            && segments[4].equals("scroll")) {
            return Endpoint.SCROLL;
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

    private synchronized ObjectNode find(String name) {
        return collections.get(name);
    }

    private void collectionInfo(HttpExchange exchange, String name) throws IOException {
        ObjectNode info = find(name);

        if (info == null) {
            notFound(exchange, name);
            return;
        }

        ok(exchange, info);
    }

    private void scroll(HttpExchange exchange, String name, JsonNode request) throws IOException {
        if (find(name) == null) {
            notFound(exchange, name);
            return;
        }

        List<ObjectNode> all = points.get(name);
        JsonNode offset = request == null ? null : request.get("offset");
        int start = 0;

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
