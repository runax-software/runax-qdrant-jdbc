package com.runaxsoftware.qdrant.jdbc.connection;

import com.runaxsoftware.qdrant.jdbc.client.QdrantGateway;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.HashMap;
import java.util.Map;

import static com.runaxsoftware.qdrant.jdbc.connection.ConnectionErrors.invalid;

/**
 * A {@code jdbc:qdrant://host[:port][?name=value&...]} URL, split into its parts.
 *
 * <p>This class only knows the URL's syntax: it checks the prefix, host, port, and path, and
 * decodes the parameters, but leaves what each parameter means to the connection settings. It
 * rejects an API key in the URL, so a URL holding a secret never becomes an object that could
 * be logged.
 *
 * @param host       the host name or address, without the brackets of an IPv6 address
 * @param port       Qdrant's gRPC port, {@link #DEFAULT_PORT} when the URL has none
 * @param parameters the decoded {@code ?name=value} pairs, exactly as written; a parameter
 *                   without a value maps to an empty string
 */
public record QdrantUrl(
    String host,
    int port,
    Map<String, String> parameters
) {
    /**
     * The prefix of every URL the driver accepts.
     */
    public static final String PREFIX = "jdbc:qdrant:";

    /**
     * Qdrant's default gRPC port, used when the URL has none.
     */
    public static final int DEFAULT_PORT = QdrantGateway.DEFAULT_PORT;

    /**
     * A working URL, quoted in error messages.
     */
    public static final String EXAMPLE = "jdbc:qdrant://localhost:6334";

    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    /**
     * Copies {@code parameters}, so the record can't change after it's made.
     *
     * @param host       the host name or address, without IPv6 brackets
     * @param port       Qdrant's gRPC port
     * @param parameters the decoded URL parameters
     */
    public QdrantUrl {
        parameters = Map.copyOf(parameters);
    }

    /**
     * Whether the driver handles this URL: whether it starts with {@link #PREFIX}, in any case.
     *
     * @param url the JDBC URL; must not be {@code null}
     * @return {@code true} for a Qdrant URL
     */
    public static boolean accepts(String url) {
        return url.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /**
     * Splits a URL into its parts. Error messages show the URL only up to its {@code ?}, so no
     * parameter value ends up in them.
     *
     * @param url the JDBC URL, such as {@code jdbc:qdrant://localhost:6334?tls=true}
     * @return the URL's host, port, and parameters
     * @throws SQLException an {@link SQLNonTransientConnectionException} with SQLState
     *                      {@code 08001} when the URL is missing, isn't a Qdrant URL, isn't a
     *                      valid URL, has no host, has a path, has a port outside 1 to 65535,
     *                      or has a {@code password} parameter
     */
    public static QdrantUrl parse(String url) throws SQLException {
        if (url == null) throw invalid("No URL given. Use " + EXAMPLE);
        if (!accepts(url))
            throw invalid("Not a Qdrant URL: " + withoutQuery(url) + ". Use " + EXAMPLE);

        URI uri;
        try {
            uri = new URI(url.substring("jdbc:".length()));
        } catch (URISyntaxException e) {
            String position = e.getIndex() < 0 ? "" : " at position " + (e.getIndex() + "jdbc:".length());
            throw invalid("Can't read the URL " + withoutQuery(url) + ": "
                + e.getReason() + position + ". Use " + EXAMPLE, e);
        }

        if (!"qdrant".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw invalid("Can't find a host in " + withoutQuery(url) + ". Use " + EXAMPLE);
        }

        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw invalid("Qdrant's gRPC API has no URL path; remove " + path + " from "
                + withoutQuery(url));
        }

        int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
        if (port < 1 || port > 65535) {
            throw invalid("Port " + port + " in " + withoutQuery(url) +
                " is out of range; use 1 to 65535, such as " + DEFAULT_PORT);
        }

        Map<String, String> parameters = parseParameters(uri.getRawQuery());
        if (parameters.containsKey("password")) {
            throw invalid("Put the API key in the password field, not in the URL "
                + withoutQuery(url));
        }

        return new QdrantUrl(unbracket(uri.getHost()), port, parameters);
    }

    /**
     * Splits the still-encoded query on {@code &} and each pair at its first {@code =}, then
     * decodes names and values. Splitting before decoding keeps an encoded {@code &} inside a
     * value. Empty pairs are skipped, and a repeated name keeps its last value.
     */
    private static Map<String, String> parseParameters(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();

        if (rawQuery == null || rawQuery.isEmpty()) return parameters;

        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;

            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);

            parameters.put(URLDecoder.decode(name, DEFAULT_CHARSET), URLDecoder.decode(value, DEFAULT_CHARSET));
        }

        return parameters;
    }

    /**
     * The URL up to its {@code ?}, for error messages: parameters may hold secrets.
     */
    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    /**
     * {@link URI} keeps the brackets around an IPv6 address; gRPC wants it without them.
     */
    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]")
            ? host.substring(1, host.length() - 1)
            : host;
    }
}
