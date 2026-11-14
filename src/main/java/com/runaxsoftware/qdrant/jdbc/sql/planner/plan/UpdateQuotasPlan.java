package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Change the server's quotas: the limits on memory and disk past which Qdrant refuses writes.
 *
 * @param changes the quota settings to change, by Qdrant's names; a null takes a limit away,
 *                and whatever isn't named stays as it is
 */
public record UpdateQuotasPlan(JsonNode changes) implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }
}
