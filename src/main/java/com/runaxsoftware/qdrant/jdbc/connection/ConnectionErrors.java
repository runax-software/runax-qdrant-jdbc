package com.runaxsoftware.qdrant.jdbc.connection;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;

final class ConnectionErrors {
    private ConnectionErrors() {
    }

    /**
     * A connection error for an invalid URL: it won't fix itself on retry.
     */
    static SQLException invalid(String message) {
        return invalid(message, null);
    }

    /**
     * A connection error for an invalid URL: it won't fix itself on retry.
     */
    static SQLException invalid(String message, Throwable cause) {
        return new SQLNonTransientConnectionException(message, "08001", cause);
    }
}
