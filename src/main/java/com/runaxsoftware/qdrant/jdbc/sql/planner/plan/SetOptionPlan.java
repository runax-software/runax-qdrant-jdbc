package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.runaxsoftware.qdrant.jdbc.gateway.models.RequestOption;

/**
 * Set one of the connection's request options, or reset it or every one of them. It changes
 * the connection, not what Qdrant holds, so a read-only connection runs it too.
 *
 * @param option the option, or {@code null} for every option (only when resetting)
 * @param value  the value as the option takes it, or {@code null} to reset to Qdrant's default
 */
public record SetOptionPlan(RequestOption option, Object value) implements Plan {

    @Override
    public boolean returnsRows() {
        return false;
    }

    @Override
    public boolean changesData() {
        return false;
    }
}
