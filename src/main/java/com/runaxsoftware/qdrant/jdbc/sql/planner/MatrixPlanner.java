package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.runaxsoftware.qdrant.jdbc.gateway.models.MatrixRequest;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.SelectItem;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Star;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DistanceMatrixStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.MatrixColumn;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.MatrixPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plans a SELECT from {@code distance_matrix(...)}. The WHERE is over the collection's columns,
 * since it says which points to sample from; the select list is over the matrix's own. Qdrant
 * answers the matrix as pairs of point IDs or as positions in it, from two routes, and the
 * select list says which is needed: positions only when a statement names one.
 */
final class MatrixPlanner {

    private static final String SYNTAX = "42000";
    private static final String UNKNOWN_COLUMN = "42S22";

    // What * stands for: the pair and its score, which both of Qdrant's answers hold
    private static final List<MatrixColumn> STAR = List.of(MatrixColumn.A, MatrixColumn.B,
        MatrixColumn.SCORE);

    Plan plan(DistanceMatrixStatement statement, Catalog catalog) throws SQLException {
        TableSchema schema = catalog.table(
            WritePlanner.collection(statement.table(), "read a distance matrix of"));
        ColumnDefinition vector = vector(statement, schema);

        if (statement.sample() != null && statement.sample() < 2) {
            throw new SQLSyntaxErrorException("A distance matrix samples at least 2 points, not "
                + statement.sample() + ": one point has no neighbour to compare it with", SYNTAX);
        }
        if (statement.neighbours() != null && statement.neighbours() < 1) {
            throw new SQLSyntaxErrorException("A distance matrix finds at least 1 neighbour of "
                + "each point, not " + statement.neighbours(), SYNTAX);
        }

        var columns = new ArrayList<MatrixColumn>();
        var names = new ArrayList<String>();

        for (SelectItem item : statement.items()) {
            if (item.expression() instanceof Star) {
                for (MatrixColumn column : STAR) {
                    columns.add(column);
                    names.add(column.columnName());
                }
            } else {
                MatrixColumn column = column(item, statement.alias());
                columns.add(column);
                names.add(item.alias() != null ? item.alias() : column.columnName());
            }
        }

        JsonNode filter = FilterPlanner.plan(statement.where(), schema,
            column -> SelectPlanner.find(schema, column, statement.alias(), false));

        return new MatrixPlan(
            new MatrixRequest(schema.name(), vector.source(), statement.sample(),
                statement.neighbours(), filter,
                columns.stream().anyMatch(MatrixColumn::position)),
            columns, names, statement.limit(),
            statement.offset() == null ? 0 : statement.offset());
    }

    // The vector the matrix compares by: a dense one or a multivector
    private static ColumnDefinition vector(DistanceMatrixStatement statement, TableSchema schema)
        throws SQLException {
        ColumnDefinition vector = schema.column(statement.vector())
            .filter(column -> column.kind() == ColumnKind.VECTOR)
            .orElseThrow(() -> new SQLSyntaxErrorException(schema.name() + " has no vector '"
                + statement.vector() + "': distance_matrix(collection, vector, ...) compares "
                + "the points by one of the collection's vector columns", UNKNOWN_COLUMN));

        if (vector.type() == ColumnType.SPARSE_VECTOR) {
            throw new SQLFeatureNotSupportedException("A distance matrix of sparse vector '"
                + vector.name() + "' isn't supported: Qdrant answers one with no pairs at all. "
                + "Name a dense vector of " + schema.name());
        }

        return vector;
    }

    private static MatrixColumn column(SelectItem item, String alias) throws SQLException {
        if (item.expression() instanceof Column column
            && (column.qualifier() == null || column.qualifier().equals(alias))) {
            return MatrixColumn.named(column.name()).orElseThrow(() -> unknown(column.name()));
        }

        throw new SQLFeatureNotSupportedException("A distance matrix selects its own columns, "
            + "by name: " + names() + ". The collection's columns go in the WHERE, which says "
            + "which points to sample from");
    }

    private static SQLException unknown(String name) {
        return new SQLSyntaxErrorException("Column '" + name + "' isn't one a distance matrix "
            + "has: its columns are " + names() + ". To read the points themselves, select "
            + "them by _id", UNKNOWN_COLUMN);
    }

    private static String names() {
        return String.join(", ", Arrays.stream(MatrixColumn.values())
            .map(MatrixColumn::columnName).toList());
    }
}
