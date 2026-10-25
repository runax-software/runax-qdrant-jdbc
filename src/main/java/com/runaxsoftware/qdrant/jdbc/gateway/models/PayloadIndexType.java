package com.runaxsoftware.qdrant.jdbc.gateway.models;

/**
 * The kinds of payload index Qdrant builds, by the name Qdrant gives each in {@code data_type}.
 */
public enum PayloadIndexType {
    KEYWORD("keyword"),
    INTEGER("integer"),
    FLOAT("float"),
    BOOL("bool"),
    DATETIME("datetime"),
    TEXT("text"),
    UUID("uuid"),
    GEO("geo"),

    /**
     * A type this version of the driver doesn't know, such as one added in a newer Qdrant.
     */
    UNKNOWN("unknown");

    private final String qdrantName;

    PayloadIndexType(String qdrantName) {
        this.qdrantName = qdrantName;
    }

    /**
     * The name Qdrant uses, such as {@code "keyword"}; shown in column descriptions.
     */
    public String qdrantName() {
        return qdrantName;
    }

    /**
     * Reads Qdrant's {@code data_type}; a name the driver doesn't know becomes {@link #UNKNOWN}.
     */
    public static PayloadIndexType fromQdrant(String name) {
        for (PayloadIndexType type : values()) if (type.qdrantName.equalsIgnoreCase(name)) return type;
        return UNKNOWN;
    }
}
