package com.runaxsoftware.qdrant.jdbc.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetHit;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOption;
import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOptions;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollPage;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ServerVersion;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class QdrantGateway implements AutoCloseable {
    /**
     * Qdrant's default REST port
     **/
    public static final int DEFAULT_PORT = 6333;

    private static final int MAX_ERROR_LENGTH = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final String userAgent;
    private volatile RequestOptions options = RequestOptions.NONE;

    private QdrantGateway(HttpClient http, String baseUrl, String apiKey, Duration timeout, String userAgent) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.userAgent = userAgent;
    }

    public static QdrantGateway open(String host, int port, boolean tls, String apiKey, Duration timeout,
                                     String userAgent) {
        String hostPart = host.contains(":") ? "[" + host + "]" : host;  // IPv6 needs brackets in a URL
        String baseUrl = (tls ? "https" : "http") + "://" + hostPart + ":" + port;
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();


        return new QdrantGateway(http, baseUrl, apiKey == null || apiKey.isEmpty() ? null : apiKey, timeout, userAgent);
    }

    public ServerVersion serverVersion() throws SQLException {
        JsonNode body = send(HttpMethod.GET, "/", null);  // GET / has no "result" wrapper and needs no key
        return ServerVersion.parse(body.path("version").asText(""), body.path("commit")
            .asText(null));
    }

    public List<String> listCollections() throws SQLException {
        List<String> names = new ArrayList<>();

        for (JsonNode collection : request(HttpMethod.GET, "/collections", null).path("collections")) {
            names.add(collection.path("name").asText());
        }

        return names;
    }

    /**
     * Every alias, with the collection it points at, in the order Qdrant lists them.
     */
    public List<QdrantAlias> listAliases() throws SQLException {
        return RestModels.aliases(request(HttpMethod.GET, "/aliases", null));
    }

    public QdrantCollection collection(String collection) throws SQLException {
        return RestModels.collection(collection, collectionInfo(collection));
    }

    /**
     * A collection as Qdrant describes it, unparsed, for the system tables: they report fields
     * whose shape depends on the Qdrant version, so they read the answer itself rather than a
     * model of it. Fails with SQL state {@code 42S02} when there's no such collection.
     */
    public JsonNode collectionInfo(String collection) throws SQLException {
        return request(HttpMethod.GET, "/collections/" + segment(collection), null);
    }

    /**
     * The options the requests from now on go with, as the connection's {@code SET} statements
     * leave them.
     */
    public void options(RequestOptions options) {
        this.options = options;
    }

    public RequestOptions options() {
        return options;
    }

    public ScrollPage scroll(ScrollRequest scroll) throws SQLException {
        ObjectNode body = JSON.createObjectNode()
            .put("limit", scroll.limit())
            .put("with_payload", scroll.withPayload())
            .put("with_vector", scroll.withVector());

        if (scroll.offset() != null) body.set("offset", scroll.offset());
        if (scroll.filter() != null) body.set("filter", scroll.filter());

        if (scroll.ordering() != null) {
            body.putObject("order_by")
                .put("key", scroll.ordering().key())
                .put("direction", scroll.ordering().direction());
        }

        return RestModels.scrollPage(
            request(HttpMethod.POST, "/collections/" + segment(scroll.collection()) + "/points/scroll"
                + readParameters(), body));
    }

    /**
     * The points nearest a vector, best first, through Qdrant's Query API. One call answers a
     * whole search: it returns the request's limit of points and no cursor, unlike a scroll.
     * The Query API's {@code offset} is a row count, so a request that gives one is answered
     * with the points after it and nothing has to be discarded here.
     */
    public List<ScoredPoint> query(QueryRequest query) throws SQLException {
        ObjectNode body = JSON.createObjectNode()
            .put("limit", query.limit())
            .put("with_payload", query.withPayload())
            .put("with_vector", query.withVector());

        if (query.document() != null) {
            // Qdrant embeds the text with the model, then searches with what it made
            body.putObject("query").put("text", query.document().text())
                .put("model", query.document().model());
        } else {
            ArrayNode vector = body.putObject("query").putArray("nearest");
            for (double value : query.query()) vector.add(value);
        }

        // A collection's single unnamed vector is the one Qdrant searches when none is named
        if (!query.vector().isEmpty()) body.put("using", query.vector());
        if (query.offset() > 0) body.put("offset", query.offset());
        if (query.filter() != null) body.set("filter", query.filter());

        ObjectNode params = searchParameters();
        if (!params.isEmpty()) body.set("params", params);

        return RestModels.scoredPoints(request(HttpMethod.POST,
            "/collections/" + segment(query.collection()) + "/points/query" + readParameters(),
            body));
    }

    /**
     * How many points a collection holds, or how many match a filter, through Qdrant's count
     * API. The count is exact:
     * Qdrant's estimate is a different number from one call to the same SQL to the next, and a
     * count is the whole answer rather than a hint about one.
     */
    public long count(String collection) throws SQLException {
        return count(collection, null);
    }

    /**
     * How many points a filter matches, counted exactly, as {@link #count(String)} is.
     *
     * @param filter a Qdrant filter, or {@code null} for every point
     */
    public long count(String collection, JsonNode filter) throws SQLException {
        ObjectNode body = JSON.createObjectNode().put("exact", true);
        if (filter != null) body.set("filter", filter);

        return request(HttpMethod.POST,
            "/collections/" + segment(collection) + "/points/count" + readParameters(), body)
            .path("count").asLong(0);
    }

    /**
     * Deletes the points a filter matches, and waits until Qdrant has applied it unless the
     * connection set {@code qdrant.wait} to false.
     */
    public void deletePoints(String collection, JsonNode filter) throws SQLException {
        ObjectNode body = JSON.createObjectNode();
        body.set("filter", filter);

        request(HttpMethod.POST,
            "/collections/" + segment(collection) + "/points/delete" + writeParameters(), body);
    }

    /**
     * Applies payload changes in order, through Qdrant's batch update API: one call, so the
     * changes of one UPDATE reach Qdrant together.
     *
     * @param operations the batch's operations, as Qdrant takes them
     */
    public void updatePoints(String collection, List<JsonNode> operations) throws SQLException {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode list = body.putArray("operations");
        operations.forEach(list::add);

        request(HttpMethod.POST,
            "/collections/" + segment(collection) + "/points/batch" + writeParameters(), body);
    }

    /**
     * The IDs among these that a collection already has a point for.
     */
    public List<JsonNode> existingIds(String collection, List<JsonNode> ids) throws SQLException {
        ObjectNode body = JSON.createObjectNode().put("with_payload", false).put("with_vector", false);
        ArrayNode list = body.putArray("ids");
        ids.forEach(list::add);

        var found = new ArrayList<JsonNode>();
        for (JsonNode point : request(HttpMethod.POST,
            "/collections/" + segment(collection) + "/points" + readParameters(), body)) {
            found.add(point.path("id"));
        }

        return found;
    }

    /**
     * Adds points, in one upsert that leaves any point already there as it is.
     *
     * @param points the points as Qdrant takes them: id, payload, and vector
     */
    public void insertPoints(String collection, List<JsonNode> points) throws SQLException {
        ObjectNode body = JSON.createObjectNode().put("update_mode", "insert_only");
        ArrayNode list = body.putArray("points");
        points.forEach(list::add);

        request(HttpMethod.PUT,
            "/collections/" + segment(collection) + "/points" + writeParameters(), body);
    }

    /**
     * Deletes a collection, with its points and indexes. Fails with SQL state {@code 42S02} when
     * there's no such collection.
     */
    public void deleteCollection(String collection) throws SQLException {
        // Some Qdrant versions answer a missing collection with "result": false, not a 404
        if (!request(HttpMethod.DELETE, "/collections/" + segment(collection), null).asBoolean()) {
            throw new SQLException("Collection `" + collection + "` doesn't exist", "42S02");
        }
    }

    /**
     * Whether a collection, or an alias of one, exists.
     */
    public boolean collectionExists(String collection) throws SQLException {
        return request(HttpMethod.GET, "/collections/" + segment(collection) + "/exists", null)
            .path("exists").asBoolean();
    }

    /**
     * Creates a collection.
     *
     * @param config its vectors and settings, as Qdrant's create-collection API takes them
     */
    public void createCollection(String collection, JsonNode config) throws SQLException {
        request(HttpMethod.PUT, "/collections/" + segment(collection), config);
    }

    /**
     * Creates a payload index, and waits until Qdrant has built it.
     *
     * @param type Qdrant's name for the index type, such as {@code keyword}
     */
    public void createIndex(String collection, String field, String type) throws SQLException {
        ObjectNode body = JSON.createObjectNode().put("field_name", field).put("field_schema", type);

        request(HttpMethod.PUT, "/collections/" + segment(collection) + "/index?wait=true", body);
    }

    /**
     * How many points hold each value of a payload field, through Qdrant's facet API, the most
     * common value first. The field needs an index Qdrant can facet on; the request's limit
     * says how many values to count, since Qdrant counts the ten most common and returns no
     * cursor when a request gives none.
     */
    public List<FacetHit> facet(FacetRequest facet) throws SQLException {
        ObjectNode body = JSON.createObjectNode()
            .put("key", facet.key())
            .put("limit", facet.limit())
            .put("exact", true);

        if (facet.filter() != null) body.set("filter", facet.filter());

        return RestModels.facetHits(request(HttpMethod.POST,
            "/collections/" + segment(facet.collection()) + "/facet" + readParameters(), body));
    }

    // The query string a read goes with: its consistency and server-side timeout, when set
    private String readParameters() {
        RequestOptions current = options;
        var parameters = new StringBuilder();

        append(parameters, "consistency", current.get(RequestOption.CONSISTENCY));
        append(parameters, "timeout", current.get(RequestOption.TIMEOUT));

        return parameters.toString();
    }

    // The query string a write goes with: whether to wait, its ordering, and its timeout
    private String writeParameters() {
        RequestOptions current = options;
        var parameters = new StringBuilder();

        append(parameters, "wait", current.waits());
        append(parameters, "ordering", current.get(RequestOption.ORDERING));
        append(parameters, "timeout", current.get(RequestOption.TIMEOUT));

        return parameters.toString();
    }

    // A search's precision settings, which go in its body rather than its query string
    private ObjectNode searchParameters() {
        RequestOptions current = options;
        ObjectNode params = JSON.createObjectNode();

        if (current.get(RequestOption.HNSW_EF) instanceof Integer ef) params.put("hnsw_ef", ef);
        if (current.get(RequestOption.EXACT) instanceof Boolean exact) params.put("exact", exact);
        if (current.get(RequestOption.INDEXED_ONLY) instanceof Boolean indexed) {
            params.put("indexed_only", indexed);
        }

        return params;
    }

    private static void append(StringBuilder parameters, String name, Object value) {
        if (value == null) return;

        parameters.append(parameters.length() == 0 ? '?' : '&').append(name).append('=')
            .append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
    }

    /**
     * Any endpoint; returns the response's "result".
     */
    public JsonNode request(HttpMethod method, String path, JsonNode body) throws SQLException {
        JsonNode result = send(method, path, body).get("result");
        if (result == null) throw new SQLException("Qdrant sent a response without a result", "08S01");
        return result;
    }

    /**
     * An endpoint's body as it was sent, for {@code GET /metrics}: Qdrant answers that one in
     * Prometheus' text format, which has no JSON {@code "result"} for {@link #request} to
     * unwrap. Failures are reported the same way as a JSON endpoint's.
     */
    public String requestText(String path) throws SQLException {
        return checkText(exchange(HttpMethod.GET, path, null, "text/plain"));
    }

    public String baseUrl() {
        return this.baseUrl;
    }

    /**
     * Nothing to close: java.net.http.HttpClient only gets close() in Java 21.
     */
    @Override
    public void close() {
    }

    private JsonNode send(HttpMethod method, String path, JsonNode body) throws SQLException {
        return check(exchange(method, path, body, "application/json"));
    }

    /**
     * One call to Qdrant, with the connection's key, timeout, and user agent, failing with the
     * SQL state the transport error deserves. The answer's status isn't looked at here: what a
     * body means depends on whether it's JSON or text.
     */
    private HttpResponse<String> exchange(HttpMethod method, String path, JsonNode body,
                                          String accept) throws SQLException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout).header("Accept", accept).header("User-Agent", userAgent);

        if (apiKey != null) request.header("api-key", apiKey);

        if (body == null) request.method(method.method(), HttpRequest.BodyPublishers.noBody());
        else {
            request.header("Content-Type", "application/json")
                .method(method.method(), HttpRequest.BodyPublishers.ofString(body.toString()));
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new SQLNonTransientConnectionException("Can't reach Qdrant at " + baseUrl + ": "
                + describe(e) + ". Check the host and that the port is Qdrant's REST port ("
                + DEFAULT_PORT + " by default)", "08001", e);
        } catch (HttpTimeoutException e) {
            throw new SQLTimeoutException("Qdrant at " + baseUrl + " didn't answer in time", "HYT00", e);
        } catch (IOException e) {
            throw new SQLNonTransientConnectionException("Request to Qdrant at " + baseUrl + " failed: "
                + describe(e), "08006", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for Qdrant", "HY008", e);
        }

        return response;
    }

    private static JsonNode check(HttpResponse<String> response) throws SQLException {
        if (!succeeded(response)) throw failure(response);

        try {
            return JSON.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new SQLException("Qdrant sent a response that isn't JSON", "08S01", e);
        }
    }

    /**
     * A successful answer's body, unparsed, for the endpoints that answer in text.
     */
    private static String checkText(HttpResponse<String> response) throws SQLException {
        if (!succeeded(response)) throw failure(response);

        return response.body();
    }

    private static boolean succeeded(HttpResponse<String> response) {
        int status = response.statusCode();
        return status >= 200 && status < 300;
    }

    private static SQLException failure(HttpResponse<String> response) {
        int status = response.statusCode();
        String error = errorMessage(response.body(), status);

        if (status == 401 || status == 403) {
            return new SQLInvalidAuthorizationSpecException(
                "Qdrant rejected the API key (put it in the password field): " + error, "28000", status);
        }

        String state = status == 404 ? "42S02" : status < 500 ? "42000" : "HY000";
        return new SQLException("Qdrant: " + error, state, status);
    }

    /**
     * Qdrant's own message for a failed request: {@code status.error} in a JSON body, or the
     * plain-text body, which Qdrant sends for a missing or wrong API key. Long bodies are cut, so
     * an HTML error page from a proxy in front of Qdrant doesn't flood the message.
     */
    private static String errorMessage(String body, int status) {
        if (body == null || body.isBlank()) return "HTTP " + status;

        try {
            String error = JSON.readTree(body).path("status").path("error").asText("");
            if (!error.isEmpty()) return error;
        } catch (JsonProcessingException e) {
            // Not JSON, such as the plain-text answer to a missing API key: use the text below.
        }

        String text = body.strip();
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) + "..." : text;
    }

    /**
     * A collection name as one URL path segment.
     */
    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
