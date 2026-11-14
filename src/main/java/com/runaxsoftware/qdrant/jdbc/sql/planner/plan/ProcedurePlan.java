package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.procedure.Procedure;

/**
 * Run one of the driver's procedures, as the request it makes.
 *
 * @param procedure the procedure a CALL named
 * @param method    the request's method
 * @param path      its path, with the call's arguments in it and its query string after
 * @param body      its JSON body, or {@code null} when the request has none
 */
public record ProcedurePlan(Procedure procedure, HttpMethod method, String path, JsonNode body)
    implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
