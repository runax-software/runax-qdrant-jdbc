package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression;

import java.util.List;

/**
 * A dense vector written out in a statement, as {@code [0.1, 0.2, 0.3]}, for searching without
 * embedding anything first. Whole numbers are values like any other, so {@code [1, 0]} is a
 * vector of two.
 *
 * @param values the vector's values, in order; never empty
 */
public record VectorLiteral(List<Double> values) implements Expression {

    public VectorLiteral(List<Double> values) {
        this.values = List.copyOf(values);
    }

    /**
     * The number of dimensions, which must match the vector it searches.
     */
    public int size() {
        return values.size();
    }
}
