package com.runaxsoftware.qdrant.jdbc.sql.executor;

import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetHit;
import com.runaxsoftware.qdrant.jdbc.gateway.models.FacetRequest;

import java.sql.SQLException;
import java.util.List;

/**
 * Counts the points per value of a payload field: {@code gateway::facet} when running, or
 * counts built by hand in tests.
 */
@FunctionalInterface
interface Faceter {

    List<FacetHit> facet(FacetRequest facet) throws SQLException;
}
