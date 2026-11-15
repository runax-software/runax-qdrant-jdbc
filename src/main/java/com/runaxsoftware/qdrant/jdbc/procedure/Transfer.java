package com.runaxsoftware.qdrant.jdbc.procedure;

/**
 * Whether a procedure's request moves a file between Qdrant and the machine the driver runs on.
 */
public enum Transfer {

    /** No file: the request and its answer are JSON. */
    NONE,

    /** Qdrant's answer is a file, which the driver writes where the call says. */
    DOWNLOAD,

    /** The request's body is a file the call names, which the driver sends as a form upload. */
    UPLOAD
}
