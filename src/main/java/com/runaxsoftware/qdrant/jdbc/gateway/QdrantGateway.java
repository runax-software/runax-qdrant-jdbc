package com.runaxsoftware.qdrant.jdbc.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
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

    public ScrollPage scroll(ScrollRequest scroll) throws SQLException {
        ObjectNode body = JSON.createObjectNode()
            .put("limit", scroll.limit())
            .put("with_payload", scroll.withPayload())
            .put("with_vector", scroll.withVector());

        if (scroll.offset() != null) body.set("offset", scroll.offset());

        return RestModels.scrollPage(
            request(HttpMethod.POST, "/collections/" + segment(scroll.collection()) + "/points/scroll", body));
    }

    /**
     * Any endpoint; returns the response's "result".
     */
    public JsonNode request(HttpMethod method, String path, JsonNode body) throws SQLException {
        JsonNode result = send(method, path, body).get("result");
        if (result == null) throw new SQLException("Qdrant sent a response without a result", "08S01");
        return result;
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
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout).header("Accept", "application/json").header("User-Agent", userAgent);

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

        return check(response);
    }

    private static JsonNode check(HttpResponse<String> response) throws SQLException {
        int status = response.statusCode();

        if (status >= 200 && status < 300) {
            try {
                return JSON.readTree(response.body());
            } catch (JsonProcessingException e) {
                throw new SQLException("Qdrant sent a response that isn't JSON", "08S01", e);
            }
        }

        String error = errorMessage(response.body(), status);
        if (status == 401 || status == 403) {
            throw new SQLInvalidAuthorizationSpecException(
                "Qdrant rejected the API key (put it in the password field): " + error, "28000", status);
        }

        String state = status == 404 ? "42S02" : status < 500 ? "42000" : "HY000";
        throw new SQLException("Qdrant: " + error, state, status);
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
