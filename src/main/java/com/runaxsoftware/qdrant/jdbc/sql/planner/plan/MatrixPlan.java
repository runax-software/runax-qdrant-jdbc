package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.runaxsoftware.qdrant.jdbc.gateway.models.MatrixRequest;

import java.util.List;

/**
 * Read a distance matrix: sampled points of a collection, each with its nearest neighbours.
 *
 * @param request what to ask Qdrant for: as offsets when a column is a position in the matrix,
 *                as pairs otherwise
 * @param columns the result set's columns, in SELECT order
 * @param names   what the statement calls each of them, an alias or the column's own name
 * @param limit   the maximum number of rows, or {@code null} when the statement gave none
 * @param offset  the rows to pass over, which the driver does: the matrix arrives whole
 */
public record MatrixPlan(MatrixRequest request, List<MatrixColumn> columns, List<String> names,
                         Long limit, long offset) implements Plan {

    public MatrixPlan {
        columns = List.copyOf(columns);
        names = List.copyOf(names);
    }
}
