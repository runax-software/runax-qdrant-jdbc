package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollPage;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;
import com.runaxsoftware.qdrant.jdbc.resultset.AbstractResultSet;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

final class ScrollResultSet extends AbstractResultSet {
    private final Scroller scroller;
    private final ScrollPlan plan;
    private final Deque<Point> buffer = new ArrayDeque<>();   // fetched but not yet returned
    private Point current;
    private JsonNode nextOffset;                               // where the next page starts
    private boolean exhausted;                                 // Qdrant has no more pages
    private long returned;                                     // rows returned, for LIMIT
    private final Long limit;      // null means no limit
    private final int pageSize;

    /**
     * Reads the first page right away, so a missing collection or a rejected key is reported by
     * executeQuery and not by the first next().
     *
     * @param scroller  where the points are read from
     * @param plan      the collection and columns to read
     * @param limit     the most rows to return, or {@code null} for no limit
     * @param pageSize  how many points one scroll request asks for
     * @param statement the statement that produced the result
     */
    ScrollResultSet(Scroller scroller, ScrollPlan plan, Long limit, int pageSize, Statement statement)
        throws SQLException {
        super(PointValues.resultColumns(plan.columns(), plan.collection()), statement);
        this.scroller = scroller;
        this.plan = plan;
        this.limit = limit;
        this.pageSize = pageSize;

        if (!limitReached()) {
            fill();
        }
    }

    @Override
    protected boolean advance() throws SQLException {
        if (limitReached()) {
            return false;
        }

        fill();
        current = buffer.poll();
        if (current == null) {
            return false;
        }

        returned++;
        return true;
    }

    @Override
    protected boolean hasNextRow() throws SQLException {
        if (limitReached()) {
            return false;
        }

        fill();
        return !buffer.isEmpty();
    }

    @Override
    protected Object currentValue(int columnIndex) throws SQLException {
        // A scroll ranks nothing, so it has no score to pass
        return PointValues.of(current, null, plan.columns().get(columnIndex - 1));
    }

    @Override
    protected void release() {
        buffer.clear();
        current = null;
    }

    private void fill() throws SQLException {
        while (buffer.isEmpty() && !exhausted) {
            int size = limit == null
                ? pageSize
                : (int) Math.min(pageSize, limit - returned);

            ScrollPage page = scroller.scroll(new ScrollRequest(
                plan.collection(), size, nextOffset, plan.withPayload(), plan.withVector()));

            buffer.addAll(page.points());
            nextOffset = page.nextPageOffset();
            exhausted = nextOffset == null;
        }
    }

    private boolean limitReached() {
        return limit != null && returned >= limit;
    }
}
