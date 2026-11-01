package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOption;

import java.util.List;

/**
 * Show the connection's request options: one row per option, its name and its value.
 *
 * @param options the options to show, in order
 */
public record ShowOptionsPlan(List<RequestOption> options) implements Plan {

    public ShowOptionsPlan {
        options = List.copyOf(options);
    }
}
