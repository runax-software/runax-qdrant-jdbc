package com.runaxsoftware.qdrant.jdbc.gateway.models;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An option a connection sends with its requests to Qdrant, set with
 * {@code SET qdrant.<option> = <value>}. Each says which requests it goes with and which values
 * Qdrant takes for it.
 */
public enum RequestOption {
    /**
     * Read consistency: how many replicas a read waits for, as a number, {@code majority},
     * {@code quorum}, or {@code all}.
     */
    CONSISTENCY("consistency"),

    /**
     * Write ordering: {@code weak}, {@code medium}, or {@code strong}.
     */
    ORDERING("ordering"),

    /**
     * Whether a write waits for Qdrant to apply it; {@code true} unless set.
     */
    WAIT("wait"),

    /**
     * Seconds Qdrant may spend on a request before it gives up.
     */
    TIMEOUT("timeout"),

    /**
     * How many candidates a search looks at: more is slower and more exact.
     */
    HNSW_EF("hnsw_ef"),

    /**
     * Whether a search checks every point instead of the index.
     */
    EXACT("exact"),

    /**
     * Whether a search reads only indexed points, leaving out ones not indexed yet.
     */
    INDEXED_ONLY("indexed_only");

    private static final Set<String> CONSISTENCIES = Set.of("majority", "quorum", "all");
    private static final Set<String> ORDERINGS = Set.of("weak", "medium", "strong");

    private final String key;

    RequestOption(String key) {
        this.key = key;
    }

    /**
     * The option's name after {@code qdrant.}, which is also Qdrant's own name for it.
     */
    public String key() {
        return key;
    }

    public static Optional<RequestOption> named(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        return Arrays.stream(values()).filter(x -> x.key.equals(lower)).findFirst();
    }

    public static String names() {
        return Arrays.stream(values()).map(x -> "qdrant." + x.key).collect(Collectors.joining(", "));
    }

    /**
     * The value as Qdrant takes it: text for consistency and ordering, a whole number for the
     * counts, a boolean for the switches. Text that holds a number or a boolean is read as one,
     * so {@code SET qdrant.timeout = '30'} works as {@code = 30} does.
     */
    public Object value(Object given) throws SQLException {
        return switch (this) {
            case CONSISTENCY -> consistency(given);
            case ORDERING -> oneOf(given, ORDERINGS);
            case WAIT, EXACT, INDEXED_ONLY -> bool(given);
            case TIMEOUT, HNSW_EF -> positive(given);
        };
    }

    private Object consistency(Object given) throws SQLException {
        if (given instanceof Long || given instanceof String text && text.strip().matches("\\d+")) {
            return positive(given);
        }

        return oneOf(given, CONSISTENCIES);
    }

    private String oneOf(Object given, Set<String> allowed) throws SQLException {
        String text = given instanceof String value ? value.strip().toLowerCase(Locale.ROOT) : null;

        if (text == null || !allowed.contains(text)) {
            throw invalid(given, String.join(", ", allowed.stream().sorted().toList()));
        }

        return text;
    }

    private boolean bool(Object given) throws SQLException {
        if (given instanceof Boolean value) return value;
        if (given instanceof String text && Set.of("true", "false").contains(text.strip().toLowerCase(Locale.ROOT))) {
            return Boolean.parseBoolean(text.strip());
        }

        throw invalid(given, "true or false");
    }

    private int positive(Object given) throws SQLException {
        long number;

        if (given instanceof Long value) {
            number = value;
        } else if (given instanceof String text && text.strip().matches("\\d{1,9}")) {
            number = Long.parseLong(text.strip());
        } else {
            throw invalid(given, "a whole number of 1 or more");
        }

        if (number < 1 || number > Integer.MAX_VALUE) {
            throw invalid(given, "a whole number of 1 or more");
        }

        return (int) number;
    }

    private SQLException invalid(Object given, String expected) {
        String shown = given instanceof String text ? "'" + text + "'" : String.valueOf(given);

        return new SQLSyntaxErrorException("qdrant." + key + " takes " + expected + ", not "
            + shown, "22023");
    }
}
