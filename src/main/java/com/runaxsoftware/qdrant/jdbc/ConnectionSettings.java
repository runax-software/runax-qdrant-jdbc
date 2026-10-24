package com.runaxsoftware.qdrant.jdbc;

import com.runaxsoftware.qdrant.jdbc.client.QdrantGateway;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Connection settings, read from a {@code jdbc:qdrant://host[:port][?name=value&...]} URL and
 * the connection properties. Properties override URL parameters. The port is Qdrant's gRPC
 * port, 6334 by default. The API key is only taken from the {@code password} property, never
 * from the URL, so it stays out of the URL that tools save and show.
 *
 * @param host       the server's host name or address
 * @param port       the server's gRPC port
 * @param tls        whether to connect over TLS
 * @param apiKey     the API key, or {@code null} for none
 * @param timeout    how long to wait for a connection and for each response
 * @param sampleSize how many points to sample when working out a collection's columns
 * @param logFile    the file to log JDBC calls to, or {@code null} for no log
 */
public record ConnectionSettings(
    String host,
    int port,
    boolean tls,
    String apiKey,
    Duration timeout,
    int sampleSize,
    Path logFile) {

    /**
     * The prefix of every URL the driver accepts.
     */
    public static final String URL_PREFIX = "jdbc:qdrant:";

    /**
     * Qdrant's default gRPC port.
     */
    public static final int DEFAULT_PORT = QdrantGateway.DEFAULT_PORT;

    /**
     * Property holding the API key.
     */
    public static final String PASSWORD = "password";

    /**
     * Property: {@code true} to connect over TLS.
     */
    public static final String TLS = "tls";

    /**
     * Property: request timeout in seconds.
     */
    public static final String TIMEOUT = "timeout";

    /**
     * Property: how many points to sample when working out a collection's columns.
     */
    public static final String SAMPLE_SIZE = "sampleSize";

    /**
     * Property: file to log every JDBC call to, for troubleshooting.
     */
    public static final String LOG_FILE = "logFile";

    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    static final int DEFAULT_SAMPLE_SIZE = 100;
    static final int MAX_SAMPLE_SIZE = 10_000;

    private static final String EXAMPLE = "jdbc:qdrant://localhost:6334";

    /**
     * Whether the driver handles this URL.
     */
    public static boolean accepts(String url) {
        return url.regionMatches(true, 0, URL_PREFIX, 0, URL_PREFIX.length());
    }

    /**
     * Reads the settings from a URL and connection properties.
     */
    public static ConnectionSettings parse(String url, Properties info) throws SQLException {
        if (url == null || !accepts(url)) {
            throw invalid("Not a Qdrant URL: " + url + ". Use " + EXAMPLE);
        }

        URI uri;
        try {
            uri = new URI(url.substring("jdbc:".length()));
        } catch (URISyntaxException e) {
            throw invalid("Can't read the URL " + url + ": " + e.getReason() + ". Use " + EXAMPLE);
        }

        if (!"qdrant".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw invalid("Can't find a host in " + url + ". Use " + EXAMPLE);
        }

        Map<String, String> settings = queryParameters(uri.getRawQuery());
        if (settings.containsKey(PASSWORD)) {
            throw invalid("Put the API key in the password field, not in the URL");
        }

        for (String name : info.stringPropertyNames()) {
            settings.put(name, info.getProperty(name));
        }

        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw invalid("Qdrant's gRPC API has no URL path; remove " + path + " from the URL");
        }

        boolean tls = parseBoolean(settings.get(TLS), TLS);
        int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();

        int timeout = parseInt(settings.get(TIMEOUT), TIMEOUT, DEFAULT_TIMEOUT_SECONDS, 1,
            Integer.MAX_VALUE);
        int sampleSize = parseInt(settings.get(SAMPLE_SIZE), SAMPLE_SIZE, DEFAULT_SAMPLE_SIZE, 0,
            MAX_SAMPLE_SIZE);
        String apiKey = settings.get(PASSWORD);

        return new ConnectionSettings(unbracket(uri.getHost()), port, tls,
            apiKey == null || apiKey.isEmpty() ? null : apiKey, Duration.ofSeconds(timeout),
            sampleSize, parsePath(settings.get(LOG_FILE)));
    }

    /**
     * Leaves out the API key, so the settings are safe to log.
     */
    @Override
    public String toString() {
        return "ConnectionSettings[host=" + host
            + ", port=" + port
            + ", tls=" + tls
            + ", apiKey=" + (apiKey == null ? "none" : "set")
            + ", timeout=" + timeout.toSeconds() + "s"
            + ", sampleSize=" + sampleSize
            + ", logFile=" + logFile + "]";
    }

    private static Map<String, String> queryParameters(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parameters;
        }

        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }

            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            parameters.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                URLDecoder.decode(value, StandardCharsets.UTF_8));
        }

        return parameters;
    }

    private static boolean parseBoolean(String value, String name) throws SQLException {
        if (value == null || value.isBlank()) {
            return false;
        }

        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid(name + " must be true or false, not " + value);
        };
    }

    private static int parseInt(String value, String name, int defaultValue, int min, int max)
        throws SQLException {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            int number = Integer.parseInt(value.strip());
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException e) {
            // Reported below.
        }

        throw invalid(name + " must be a whole number from " + min + " to " + max + ", not " + value);
    }

    private static Path parsePath(String value) throws SQLException {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Path.of(value.strip());
        } catch (InvalidPathException e) {
            throw invalid(LOG_FILE + " isn't a valid path: " + value);
        }
    }

    /**
     * {@link URI} keeps the brackets around IPv6 addresses; gRPC wants them without.
     */
    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]")
            ? host.substring(1, host.length() - 1)
            : host;
    }

    private static SQLException invalid(String message) {
        return new SQLException(message, "08001");
    }
}
