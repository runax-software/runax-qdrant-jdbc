package com.runaxsoftware.qdrant.jdbc.procedure;

import java.sql.Types;

/**
 * What a procedure's argument takes as its value.
 */
public enum ArgumentKind {

    /** Text, such as a collection's name. */
    TEXT(Types.VARCHAR, "VARCHAR", "text"),

    /** A whole number of 0 or more, such as a shard's or a peer's id. */
    WHOLE(Types.BIGINT, "BIGINT", "a whole number of 0 or more"),

    /** {@code true} or {@code false}. */
    BOOL(Types.BOOLEAN, "BOOLEAN", "true or false"),

    /** A shard key: text or a whole number, the two kinds Qdrant takes. */
    KEY(Types.VARCHAR, "VARCHAR", "text or a whole number"),

    /** JSON text, which Qdrant checks. */
    JSON(Types.VARCHAR, "JSON", "JSON text");

    private final int sqlType;
    private final String typeName;
    private final String expected;

    ArgumentKind(int sqlType, String typeName, String expected) {
        this.sqlType = sqlType;
        this.typeName = typeName;
        this.expected = expected;
    }

    /**
     * The {@link Types} constant {@code DatabaseMetaData} reports the argument as.
     */
    public int sqlType() {
        return sqlType;
    }

    public String typeName() {
        return typeName;
    }

    /**
     * What the argument takes, to end "takes ..." with in a message.
     */
    public String expected() {
        return expected;
    }
}
