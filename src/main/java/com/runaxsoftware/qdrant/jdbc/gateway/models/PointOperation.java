package com.runaxsoftware.qdrant.jdbc.gateway.models;

import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;

import java.util.Arrays;
import java.util.Optional;

/**
 * One kind of change to points' payload or vectors. Qdrant takes each two ways: as one of the
 * operations of a batch, under its name, or alone at a route of its own, with the same body.
 */
public enum PointOperation {

    /** Sets payload fields, keeping the others. */
    SET_PAYLOAD("set_payload", HttpMethod.POST, "/points/payload"),

    /** Replaces the whole payload. */
    OVERWRITE_PAYLOAD("overwrite_payload", HttpMethod.PUT, "/points/payload"),

    /** Deletes payload fields. */
    DELETE_PAYLOAD("delete_payload", HttpMethod.POST, "/points/payload/delete"),

    /** Deletes the whole payload. */
    CLEAR_PAYLOAD("clear_payload", HttpMethod.POST, "/points/payload/clear"),

    /** Writes vectors to points named by ID. */
    UPDATE_VECTORS("update_vectors", HttpMethod.PUT, "/points/vectors"),

    /** Deletes named vectors from points. */
    DELETE_VECTORS("delete_vectors", HttpMethod.POST, "/points/vectors/delete");

    private final String operationName;
    private final HttpMethod method;
    private final String path;

    PointOperation(String operationName, HttpMethod method, String path) {
        this.operationName = operationName;
        this.method = method;
        this.path = path;
    }

    /**
     * The operation's name in a batch, which holds its body under it.
     */
    public String operationName() {
        return operationName;
    }

    /**
     * The method of the route that takes the operation alone.
     */
    public HttpMethod method() {
        return method;
    }

    /**
     * That route's path, after the collection's.
     */
    public String path() {
        return path;
    }

    /**
     * The operation with this name in a batch, or empty when Qdrant has none the driver knows.
     */
    public static Optional<PointOperation> named(String name) {
        return Arrays.stream(values()).filter(operation -> operation.operationName.equals(name))
            .findFirst();
    }
}
