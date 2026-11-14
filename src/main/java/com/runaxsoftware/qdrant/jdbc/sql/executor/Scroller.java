package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.runaxsoftware.qdrant.jdbc.gateway.models.Point;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollPage;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;

import java.sql.SQLException;
import java.util.List;

/**
 * Reads one page of points: {@code gateway::scroll} when running, or pages built by hand in tests.
 */
@FunctionalInterface
interface Scroller {

    ScrollPage scroll(ScrollRequest request) throws SQLException;

    /**
     * The point with an ID, or {@code null} when the collection has none. A scroller that only
     * pages finds it the way it finds any point: by scrolling for it.
     */
    default Point point(String collection, JsonNode id) throws SQLException {
        JsonNode filter = JsonNodeFactory.instance.objectNode().set("must",
            JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode()
                .set("has_id", JsonNodeFactory.instance.arrayNode().add(id))));
        List<Point> points = scroll(new ScrollRequest(collection, 1, null, true, true, filter,
            null)).points();

        return points.isEmpty() ? null : points.get(0);
    }
}
