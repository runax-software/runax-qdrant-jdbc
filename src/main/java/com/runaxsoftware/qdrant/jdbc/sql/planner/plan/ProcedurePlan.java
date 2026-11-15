package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.procedure.Procedure;

import java.nio.file.Path;

/**
 * Run one of the driver's procedures, as the request it makes.
 *
 * @param procedure the procedure a CALL named
 * @param method    the request's method
 * @param path      its path, with the call's arguments in it and its query string after
 * @param body      its JSON body, or {@code null} when the request has none
 * @param file      the file on this machine the request's answer is saved to or its body is
 *                  read from, as the procedure's {@code transfer()} says, or {@code null} when
 *                  it moves none
 * @param restores  the collection the request replaces with a snapshot, whose columns may be
 *                  different afterwards, or {@code null} when it replaces none
 */
public record ProcedurePlan(Procedure procedure, HttpMethod method, String path, JsonNode body,
                            Path file, String restores) implements Plan {

    /**
     * A procedure that moves no file and restores no collection.
     */
    public ProcedurePlan(Procedure procedure, HttpMethod method, String path, JsonNode body) {
        this(procedure, method, path, body, null, null);
    }

    @Override
    public boolean returnsRows() {
        return false;
    }
}
