package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

import java.util.List;

/**
 * A SELECT from {@code distance_matrix(collection, vector [, sample [, neighbours]])}: sampled
 * points of a collection, each with its nearest neighbours among the sample.
 *
 * @param items      the select list, in order
 * @param table      the collection to sample
 * @param vector     the vector column to compare by
 * @param sample     how many points to sample, or {@code null} when the call doesn't say
 * @param neighbours how many neighbours to find for each, or {@code null} likewise
 * @param alias      the name the statement gives the matrix, or {@code null}
 * @param where      the points to sample from, as a WHERE over the collection's columns, or
 *                   {@code null}
 * @param limit      the LIMIT, or {@code null}
 * @param offset     the OFFSET, or {@code null}
 */
public record DistanceMatrixStatement(List<SelectItem> items, TableName table, String vector,
                                      Long sample, Long neighbours, String alias,
                                      Expression where, Long limit, Long offset)
    implements Statement {

    public DistanceMatrixStatement {
        items = List.copyOf(items);
    }
}
