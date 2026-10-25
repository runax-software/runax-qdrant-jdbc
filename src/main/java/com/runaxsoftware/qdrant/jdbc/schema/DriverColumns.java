package com.runaxsoftware.qdrant.jdbc.schema;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public enum DriverColumns {
    ID("_id", "Point ID: an unsigned integer or a UUID"),
    PAYLOAD("_payload", "The whole payload as JSON"),
    VECTOR("_vector", "The collection's single unnamed vector"),
    SCORE("_score", "The search score"),
    GROUP("_group", "The group a result belongs to"),
    LOOKUP("_lookup", "The group's record from the lookup collection");

    private static final Map<String, DriverColumns> BY_NAME = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(DriverColumns::columnName, column -> column));

    private final String columnName;
    private final String remarks;

    DriverColumns(String columnName, String remarks) {
        this.columnName = columnName;
        this.remarks = remarks;
    }

    public String columnName() {
        return this.columnName;
    }

    public String remarks() {
        return this.remarks;
    }

    /**
     * Whether a name belongs to one of the driver's own columns, such as {@code "_id"}.
     */
    public static boolean isReserved(String name) {
        return BY_NAME.containsKey(name);
    }

    /**
     * The driver column with this name, if there is one; for the SQL layer, which meets {@code _score} and the like.
     */
    public static Optional<DriverColumns> of(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }
}
