//package com.runaxsoftware.qdrant.jdbc.sql.executor;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.runaxsoftware.qdrant.jdbc.client.Point;
//import com.runaxsoftware.qdrant.jdbc.client.QdrantGateway;
//import com.runaxsoftware.qdrant.jdbc.client.ScrollPage;
//import com.runaxsoftware.qdrant.jdbc.client.ScrollRequest;
//import com.runaxsoftware.qdrant.jdbc.result.AbstractResultSet;
//import com.runaxsoftware.qdrant.jdbc.result.ResultColumn;
//import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
//import com.runaxsoftware.qdrant.jdbc.schema.PayloadPaths;
//import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ScrollPlan;
//
//import java.sql.ResultSetMetaData;
//import java.sql.SQLException;
//import java.sql.Statement;
//import java.util.ArrayDeque;
//import java.util.Deque;
//import java.util.List;
//
//final class ScrollResultSet extends AbstractResultSet {
//
//    private static final int PAGE_SIZE = 100;
//
//    private final QdrantGateway gateway;
//    private final ScrollPlan plan;
//    private final Deque<Point> buffer = new ArrayDeque<>();   // fetched but not yet returned
//    private Point current;
//    private JsonNode nextOffset;                               // where the next page starts
//    private boolean exhausted;                                 // Qdrant has no more pages
//    private long returned;                                     // rows returned, for LIMIT
//
//    /**
//     * @param columns   the result's columns, in order
//     * @param statement the statement that produced the result, or {@code null} for metadata
//     */
//    ScrollResultSet(QdrantGateway gateway, ScrollPlan plan, Statement statement) {
//        super(resultColumns(plan), statement);
//        this.gateway = gateway;
//        this.plan = plan;
//    }
//
//    @Override
//    protected boolean advance() throws SQLException {
//        if (limitReached()) {
//            return false;
//        }
//
//        fill();
//        current = buffer.poll();
//        if (current == null) {
//            return false;
//        }
//
//        returned++;
//        return true;
//    }
//
//    @Override
//    protected boolean hasNextRow() throws SQLException {
//        if (limitReached()) {
//            return false;
//        }
//
//        fill();
//        return !buffer.isEmpty();
//    }
//
//    @Override
//    protected Object currentValue(int columnIndex) throws SQLException {
//        ColumnDefinition column = plan.columns().get(columnIndex - 1);
//
//        return switch (column.kind()) {
//            case ID -> current.id().asText();
//            case PAYLOAD -> current.payload() == null ? null : current.payload().toString();
//            case PAYLOAD_FIELD -> value(PayloadPaths.valueAt(current.payload(), column.source()), column);
//            case VECTOR -> vector(column.source());
//        }
//    }
//
//    @Override
//    protected void release() {
//        buffer.clear();
//        current = null;
//    }
//
//    private void fill() throws SQLException {
//        while (buffer.isEmpty() && !exhausted) {
//            int size = plan.limit() == null
//                ? PAGE_SIZE
//                : (int) Math.min(PAGE_SIZE, plan.limit() - returned);
//
//            ScrollPlan page = gateway.scroll(new ScrollRequest(
//                plan.collection(), size, nextOffset, plan.withPayload(), plan.withVector()));
//
//            buffer.addAll(page.points());
//            nextOffset = page.nextPageOffset();
//            exhausted = nextOffset == null;
//        }
//    }
//
//    private boolean limitReached() {
//        return plan.limit() != null && returned >= plan.limit();
//    }
//
//    private static Object value(JsonNode node, ColumnDefinition column) {
//        if (node == null || node.isNull()) {
//            return null;
//        }
//
//        if (column.type() == ColumnType.JSON) {
//            return node.toString();
//        }
//
//        return node.isIntegralNumber() && node.canConvertToLong()
//            ? node.longValue()
//            : node.isNumber() ? node.doubleValue()
//            : node.isBoolean() ? node.booleanValue()
//            : node.toString();
//    }
//
//    private Object vector(String name) {
//        JsonNode vectors = current.vector();
//
//        if (vectors == null) {
//            return null;
//        }
//
//        JsonNode vector = name.isEmpty() ? vectors : vectors.get(name);
//
//        return vector == null || vector.isNull() ? null : vector.toString();
//    }
//
//    private static List<ResultColumn> resultColumns(ScrollPlan plan) {
//        return plan.columns().stream()
//            .map(x -> new ResultColumn(
//                x.name(),
//                x.type().sqlType(),
//                x.type().typeName(),
//                plan.collection(),
//                x.size(),
//                x.nullable()
//                    ? ResultColumnsMetaData.columnNullable
//                    : ResultColumnsMetaData.columnNoNulls))
//            .toList();
//    }
//}
