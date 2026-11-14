package com.runaxsoftware.qdrant.jdbc.sql.planner;

/**
 * What a collection setting takes as its value.
 */
enum SettingKind {

    /** {@code true} or {@code false}. */
    BOOL,

    /** A whole number of 0 or more. */
    WHOLE,

    /** A number. */
    NUMBER,

    /** A whole number of 0 or more, or {@code auto} for Qdrant to choose. */
    THREADS,

    /** A word, which Qdrant checks. */
    WORD
}
