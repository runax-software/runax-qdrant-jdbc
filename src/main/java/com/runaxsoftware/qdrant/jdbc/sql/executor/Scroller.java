package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollPage;
import com.runaxsoftware.qdrant.jdbc.gateway.models.ScrollRequest;

import java.sql.SQLException;

/**
 * Reads one page of points: {@code gateway::scroll} when running, or pages built by hand in tests.
 */
@FunctionalInterface
interface Scroller {

    ScrollPage scroll(ScrollRequest request) throws SQLException;
}
