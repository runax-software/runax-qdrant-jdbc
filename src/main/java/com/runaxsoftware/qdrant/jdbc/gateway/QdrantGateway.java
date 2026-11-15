package com.runaxsoftware.qdrant.jdbc.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetHit;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.MatrixPair;
import com.runaxsoftware.qdrant.jdbc.gateway.models.MatrixRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PointOperation;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ProbeAnswer;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QueryRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOption;
import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOptions;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScoredPoint;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollPage;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ServerVersion;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class QdrantGateway implements AutoCloseable {
    /**
     * Qdrant's default REST port
     **/
    public static final int DEFAULT_PORT = 6333;

    private static final String QUOTAS_PATH = "/quotas";

    // The state every "not found" answer gets, and what Qdrant says when it's a point it lacks
    private static final String NOT_FOUND = "42S02";
    private static final String NO_SUCH_POINT = "Point with id";
    private static final int MAX_ERROR_LENGTH = 500;

    // How much of a failed download's body is read for its message
    private static final int MAX_ERROR_BYTES = 4096;
    private static final String BINARY = "application/octet-stream";
    private static final String FILE_ERROR = "58030";

    /**
     * Points per request when reading the IDs a filter matches. They carry no payload and no
     * vectors, so a page of them is small whatever the collection holds.
     */
    private static final int SCROLL_PAGE = 1000;
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

    /**
     * The aliases that point at one collection.
     */
    public List<String> collectionAliases(String collection) throws SQLException {
        return RestModels.aliases(request(HttpMethod.GET,
            "/collections/" + segment(collection) + "/aliases", null))
            .stream().map(QdrantAlias::name).toList();
    }

    /**
     * Creates, deletes, and renames aliases, all the actions together as one atomic change, so
     * an alias moved from one collection to another always points at one of them.
     *
     * @param actions the actions as Qdrant takes them, such as {@code {"create_alias": {...}}}
     */
    public void changeAliases(List<JsonNode> actions) throws SQLException {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode list = body.putArray("actions");
        actions.forEach(list::add);

        request(HttpMethod.POST, "/collections/aliases", body);
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

    /**
     * The point with this ID, with its payload and all its vectors, or {@code null} when the
     * collection has none. Qdrant answers a missing point and a missing collection alike, as
     * not found, and only its message says which; a missing collection stays an error.
     *
     * @param id a whole number or a UUID
     */
    public Point point(String collection, JsonNode id) throws SQLException {
        JsonNode point;

        try {
            point = request(HttpMethod.GET, "/collections/" + segment(collection) + "/points/"
                + segment(id.asText()) + readParameters(), null);
        } catch (SQLException e) {
            if (NOT_FOUND.equals(e.getSQLState())
                && String.valueOf(e.getMessage()).contains(NO_SUCH_POINT)) {
                return null;
            }

            throw e;
        }

        return RestModels.point(point);
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
     * The IDs of the points a filter matches, read with the scroll cursor and nothing else:
     * no payload and no vectors. Qdrant's {@code update_vectors} takes point IDs and rejects a
     * filter alone, so an {@code UPDATE} that writes a vector reads the IDs it's going to write
     * to first.
     *
     * @param limit the most IDs to read; reading stops once more than this many exist, so the
     *              caller can tell a capped read from a complete one by the size it gets back
     * @return the matching IDs, in Qdrant's own order, at most {@code limit + 1} of them
     */
    public List<JsonNode> matchingIds(String collection, JsonNode filter, int limit)
        throws SQLException {
        var ids = new ArrayList<JsonNode>();
        JsonNode offset = null;

        do {
            int page = Math.min(SCROLL_PAGE, limit + 1 - ids.size());
            ScrollPage answered = scroll(
                new ScrollRequest(collection, page, offset, false, false, filter));

            for (Point point : answered.points()) ids.add(point.id());
            offset = answered.nextPageOffset();
        } while (offset != null && ids.size() <= limit);

        return ids;
    }

    /**
     * The points nearest a vector, or the ones whose text matches a query best, best first,
     * through Qdrant's Query API. One call answers a
     * whole search: it returns the request's limit of points and no cursor, unlike a scroll.
     * The Query API's {@code offset} is a row count, so a request that gives one is answered
     * with the points after it and nothing has to be discarded here.
     */
    public List<ScoredPoint> query(QueryRequest query) throws SQLException {
        return RestModels.scoredPoints(request(HttpMethod.POST,
            "/collections/" + segment(query.collection()) + "/points/query" + readParameters(),
            queryBody(query)));
    }

    /**
     * Several searches of one collection in one call, each answered as {@link #query} would
     * answer it, in the order given.
     *
     * @param queries the searches, all of {@code collection}; none is sent when there are none
     */
    public List<List<ScoredPoint>> queryBatch(String collection, List<QueryRequest> queries)
        throws SQLException {
        if (queries.isEmpty()) return List.of();

        ObjectNode body = JSON.createObjectNode();
        ArrayNode searches = body.putArray("searches");
        for (QueryRequest query : queries) searches.add(queryBody(query));

        var answers = new ArrayList<List<ScoredPoint>>(queries.size());
        for (JsonNode answer : request(HttpMethod.POST,
            "/collections/" + segment(collection) + "/points/query/batch" + readParameters(),
            body)) {
            answers.add(RestModels.scoredPoints(answer));
        }

        if (answers.size() != queries.size()) {
            throw new SQLException("Qdrant answered " + answers.size() + " of the "
                + queries.size() + " searches it was sent", "08S01");
        }

        return answers;
    }

    /**
     * The best points of each group, through Qdrant's grouped Query API: the groups are the
     * values of a payload field, the request's limit says how many groups, and each gives up to
     * {@code groupSize} of its closest points. The points come back group after group, the group
     * whose best point is closest first. Qdrant has no offset for groups, so the request's is
     * not sent.
     */
    public List<ScoredPoint> queryGroups(QueryRequest query, String groupBy, long groupSize)
        throws SQLException {
        ObjectNode body = queryBody(query);
        body.remove("offset");
        body.put("group_by", groupBy).put("group_size", groupSize);

        return RestModels.groupedPoints(request(HttpMethod.POST,
            "/collections/" + segment(query.collection()) + "/points/query/groups"
                + readParameters(), body));
    }

    /**
     * A distance matrix: Qdrant samples points and answers with each one's nearest neighbours
     * among the sample. It answers the same matrix two ways, from two routes: as pairs of point
     * IDs, or as row and column positions with the IDs listed once, and the request says which.
     */
    public List<MatrixPair> matrix(MatrixRequest matrix) throws SQLException {
        ObjectNode body = JSON.createObjectNode();

        if (matrix.sample() != null) body.put("sample", matrix.sample());
        if (matrix.neighbours() != null) body.put("limit", matrix.neighbours());
        if (!matrix.vector().isEmpty()) body.put("using", matrix.vector());
        if (matrix.filter() != null) body.set("filter", matrix.filter());

        JsonNode result = request(HttpMethod.POST, "/collections/" + segment(matrix.collection())
            + "/points/search/matrix/" + (matrix.offsets() ? "offsets" : "pairs")
            + readParameters(), body);

        return matrix.offsets() ? RestModels.matrixOffsets(result)
            : RestModels.matrixPairs(result);
    }

    // One search as the Query API takes it, alone or as one of a batch
    private ObjectNode queryBody(QueryRequest query) {
        ObjectNode body = JSON.createObjectNode()
            .put("limit", query.limit())
            .put("with_payload", query.withPayload())
            .put("with_vector", query.withVector());

        if (query.text() != null) {
            // BM25 over the text index of the payload field that `using` names
            body.putObject("query").put("text", query.text());
        } else if (query.document() != null) {
            // Qdrant embeds the text with the model, then searches with what it made
            body.putObject("query").put("text", query.document().text())
                .put("model", query.document().model());
        } else if (query.nearest() != null) {
            // A sparse vector or a multivector, in the shape Qdrant takes each
            body.putObject("query").set("nearest", query.nearest());
        } else {
            ArrayNode vector = body.putObject("query").putArray("nearest");
            for (double value : query.query()) vector.add(value);
        }

        // A collection's single unnamed vector is the one Qdrant searches when none is named; a
        // search by text always names its payload field
        if (!query.vector().isEmpty()) body.put("using", query.vector());
        if (query.offset() > 0) body.put("offset", query.offset());
        if (query.filter() != null) body.set("filter", query.filter());

        ObjectNode params = searchParameters();
        if (!params.isEmpty()) body.set("params", params);

        return body;
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
     * Applies changes to points' payload and vectors, in order and in one call. Several go
     * through Qdrant's batch update API, so the changes of one UPDATE reach Qdrant together. A
     * single one goes to the route Qdrant has for that kind of change alone, which takes the
     * same body the batch holds under the operation's name.
     *
     * @param operations the operations, each as a batch takes it: one object holding the
     *                   operation's body under its name
     */
    public void updatePoints(String collection, List<JsonNode> operations) throws SQLException {
        if (operations.size() == 1 && operations.get(0).size() == 1) {
            String name = operations.get(0).fieldNames().next();
            PointOperation operation = PointOperation.named(name).orElse(null);

            if (operation != null) {
                request(operation.method(), "/collections/" + segment(collection)
                    + operation.path() + writeParameters(), operations.get(0).get(name));
                return;
            }
        }

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
     * Adds points, in one upsert.
     *
     * @param points           the points as Qdrant takes them: id, payload, and vector
     * @param replaceExisting  whether a point already there is replaced, which is Qdrant's
     *                         {@code upsert} mode; otherwise {@code insert_only} leaves it as
     *                         it is
     * @param updateFilter     which of the points already there may be replaced, or
     *                         {@code null} for all of them. Qdrant applies it only to those:
     *                         a point the collection hasn't got is inserted whatever it says
     */
    public void insertPoints(String collection, List<JsonNode> points, boolean replaceExisting,
                             JsonNode updateFilter) throws SQLException {
        ObjectNode body = JSON.createObjectNode()
            .put("update_mode", replaceExisting ? "upsert" : "insert_only");
        ArrayNode list = body.putArray("points");
        points.forEach(list::add);

        if (updateFilter != null) body.set("update_filter", updateFilter);

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
     * @param schema the index as Qdrant takes it: its type, such as {@code "keyword"}, or an
     *               object of its type and options
     */
    public void createIndex(String collection, String field, JsonNode schema) throws SQLException {
        ObjectNode body = JSON.createObjectNode().put("field_name", field);
        body.set("field_schema", schema);

        request(HttpMethod.PUT, "/collections/" + segment(collection) + "/index?wait=true", body);
    }

    /**
     * Deletes the payload index on a field, and waits until Qdrant has dropped it. Qdrant
     * answers a field without an index as a success too, so a caller checks first.
     */
    public void deleteIndex(String collection, String field) throws SQLException {
        request(HttpMethod.DELETE, "/collections/" + segment(collection) + "/index/"
            + segment(field) + "?wait=true", null);
    }

    /**
     * Changes a collection's settings, or those of its vectors. Qdrant changes what the body
     * names and leaves the rest, and it leaves out a name it doesn't know without saying so.
     */
    public void updateCollection(String collection, JsonNode changes) throws SQLException {
        request(HttpMethod.PATCH, "/collections/" + segment(collection), changes);
    }

    /**
     * Adds a named vector to a collection, and waits until Qdrant has. Qdrant answers a vector
     * already there with the same settings as a success, so a caller checks first.
     *
     * @param config the vector under {@code dense} or {@code sparse}, as Qdrant takes it
     */
    public void createVector(String collection, String vector, JsonNode config)
        throws SQLException {
        request(HttpMethod.PUT, vectorPath(collection, vector), config);
    }

    /**
     * Deletes a named vector from a collection, with every point's value for it, and waits
     * until Qdrant has. Qdrant answers a vector that isn't there as a success too, so a caller
     * checks first.
     */
    public void deleteVector(String collection, String vector) throws SQLException {
        request(HttpMethod.DELETE, vectorPath(collection, vector), null);
    }

    private static String vectorPath(String collection, String vector) {
        return "/collections/" + segment(collection) + "/vectors/" + segment(vector) + "?wait=true";
    }

    /**
     * The server's quotas: under {@code config} the limits and whether they're enforced, and
     * under {@code usage} how much memory and disk is used now.
     */
    public JsonNode quotas() throws SQLException {
        return request(HttpMethod.GET, QUOTAS_PATH, null);
    }

    /**
     * Sets the server's quotas, and waits until Qdrant has. The config replaces the one there:
     * a limit it leaves out is a limit taken away, so a caller that changes one reads the rest
     * first.
     */
    public void setQuotas(JsonNode config) throws SQLException {
        request(HttpMethod.PUT, QUOTAS_PATH + "?wait=true", config);
    }

    /**
     * Forgets every issue Qdrant has reported about itself. Qdrant reports one again the next
     * time it comes across what caused it.
     */
    public void clearIssues() throws SQLException {
        request(HttpMethod.DELETE, "/issues", null);
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
     * Any endpoint that is asked to do something rather than to answer: the request succeeding
     * is the whole answer. Qdrant sends no {@code "result"} at all when it accepts work to
     * finish in the background, as it does with {@code wait=false}.
     */
    public void run(HttpMethod method, String path, JsonNode body) throws SQLException {
        send(method, path, body);
    }

    /**
     * Saves what an endpoint answers to a new file, without holding it in memory: a snapshot
     * can be larger than the heap. The connection's timeout bounds the wait for Qdrant to
     * start answering, not the transfer, whose length is the file's.
     *
     * @param path the endpoint, which answers with a file
     * @param file where to save it; a file already there is an error, not overwritten
     * @return the bytes saved
     */
    public long download(String path, Path file) throws SQLException {
        HttpRequest request = request(path, BINARY).timeout(timeout).GET().build();
        HttpResponse<InputStream> response = transmit(request,
            HttpResponse.BodyHandlers.ofInputStream());
        boolean created = false;

        try (InputStream body = response.body()) {
            if (!succeeded(response.statusCode())) {
                throw failure(response.statusCode(),
                    new String(body.readNBytes(MAX_ERROR_BYTES), StandardCharsets.UTF_8));
            }

            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
                created = true;
                return body.transferTo(out);
            }
        } catch (FileAlreadyExistsException e) {
            throw new SQLException("Can't save to " + file + ": the file is already there",
                FILE_ERROR, e);
        } catch (NoSuchFileException e) {
            throw new SQLException("Can't save to " + file + ": its folder doesn't exist",
                FILE_ERROR, e);
        } catch (AccessDeniedException e) {
            throw new SQLException("Can't save to " + file + ": no permission to write there",
                FILE_ERROR, e);
        } catch (IOException e) {
            // What was written is part of a snapshot, which is worse than none
            if (created) discard(file);

            throw new SQLException("Saving to " + file + " failed: " + describe(e), FILE_ERROR, e);
        }
    }

    /**
     * Sends a file to an endpoint as a form upload, read from disk as it goes. No timeout
     * bounds the request: it lasts as long as the file takes to send and Qdrant to recover
     * from it.
     *
     * @param path  the endpoint, with its query string
     * @param field the form field Qdrant reads the file from
     * @param file  the file to send
     */
    public void upload(String path, String field, Path file) throws SQLException {
        String boundary = "qdrant-jdbc-" + UUID.randomUUID();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field
            + "\"; filename=\"" + field + "\"\r\nContent-Type: " + BINARY + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";

        HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(head),
                HttpRequest.BodyPublishers.ofFile(file), HttpRequest.BodyPublishers.ofString(tail));
        } catch (FileNotFoundException e) {
            throw new SQLException("Can't read " + file + ": there is no such file", FILE_ERROR, e);
        }

        HttpRequest request = request(path, "application/json")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(body).build();

        check(transmit(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    private static void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // The failure that led here is the one to report
        }
    }

    /**
     * An endpoint's body as it was sent, for {@code GET /metrics}: Qdrant answers that one in
     * Prometheus' text format, which has no JSON {@code "result"} for {@link #request} to
     * unwrap. Failures are reported the same way as a JSON endpoint's.
     */
    public String requestText(String path) throws SQLException {
        return checkText(exchange(HttpMethod.GET, path, null, "text/plain"));
    }

    /**
     * What a health probe answers: {@code /healthz}, {@code /livez}, or {@code /readyz}. Unlike
     * every other call, a status that isn't a success is returned rather than thrown, since it's
     * how a probe says the server isn't ready; only failing to reach Qdrant at all is an error.
     */
    public ProbeAnswer probe(String path) throws SQLException {
        HttpResponse<String> response = exchange(HttpMethod.GET, path, null, "text/plain");
        String body = response.body();

        return new ProbeAnswer(response.statusCode(),
            body == null || body.isBlank() ? null : body.strip());
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
        HttpRequest.Builder request = request(path, accept).timeout(timeout);

        if (body == null) request.method(method.method(), HttpRequest.BodyPublishers.noBody());
        else {
            request.header("Content-Type", "application/json")
                .method(method.method(), HttpRequest.BodyPublishers.ofString(body.toString()));
        }

        return transmit(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // A request to Qdrant with what every one carries: the key and the user agent
    private HttpRequest.Builder request(String path, String accept) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Accept", accept).header("User-Agent", userAgent);

        if (apiKey != null) request.header("api-key", apiKey);

        return request;
    }

    // Sends a request, failing with the SQL state the transport error deserves
    private <T> HttpResponse<T> transmit(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws SQLException {
        HttpResponse<T> response;
        try {
            response = http.send(request, handler);
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
        return succeeded(response.statusCode());
    }

    private static boolean succeeded(int status) {
        return status >= 200 && status < 300;
    }

    private static SQLException failure(HttpResponse<String> response) {
        return failure(response.statusCode(), response.body());
    }

    private static SQLException failure(int status, String body) {
        String error = errorMessage(body, status);

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
