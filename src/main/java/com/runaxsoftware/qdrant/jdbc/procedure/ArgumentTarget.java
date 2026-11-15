package com.runaxsoftware.qdrant.jdbc.procedure;

/**
 * Where an argument's value goes in the request a procedure makes.
 */
public enum ArgumentTarget {

    /** Into the path, in place of {@code {name}}. */
    PATH,

    /** Into the JSON body, under the argument's name. */
    BODY,

    /** Into the query string, under the argument's name. */
    QUERY,

    /**
     * Nowhere in the request's path or JSON: the value is a file on the machine the driver runs
     * on, which the request's answer is written to or its body is read from.
     */
    FILE
}
