package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollPage;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;
import com.runaxsoftware.qdrant.jdbc.resultset.AbstractResultSet;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.gateway.models.PayloadPath;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;

import java.sql.ResultSetMetaData;
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
        super(resultColumns(plan), statement);
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
        ColumnDefinition column = plan.columns().get(columnIndex - 1);

        return switch (column.kind()) {
            case ID -> current.id().asText();
            case PAYLOAD -> current.payload() == null ? null : current.payload().toString();
            case PAYLOAD_FIELD -> value(PayloadPath.valueAt(current.payload(), column.source()), column);
            case VECTOR -> vector(column.source());
        };
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

    private static Object value(JsonNode node, ColumnDefinition column) {
        if (node == null || node.isNull()) {
            return null;
        }

        if (column.type() == ColumnType.JSON) {
            return node.toString();
        }

        return node.isIntegralNumber() && node.canConvertToLong()
            ? node.longValue()
            : node.isNumber() ? node.doubleValue()
            : node.isBoolean() ? node.booleanValue()
            : node.isTextual() ? node.asText()   // the text itself, without JSON's quotes
            : node.toString();
    }

    private Object vector(String name) {
        JsonNode vectors = current.vector();

        if (vectors == null) {
            return null;
        }

        JsonNode vector = name.isEmpty() ? vectors : vectors.get(name);

        return vector == null || vector.isNull() ? null : vector.toString();
    }

    private static List<ResultColumn> resultColumns(ScrollPlan plan) {
        return plan.columns().stream()
            .map(x -> new ResultColumn(
                x.name(),
                x.type().sqlType(),
                x.type().typeName(),
                plan.collection(),
                x.size(),
                x.nullable()
                    ? ResultSetMetaData.columnNullable
                    : ResultSetMetaData.columnNoNulls))
            .toList();
    }
}
