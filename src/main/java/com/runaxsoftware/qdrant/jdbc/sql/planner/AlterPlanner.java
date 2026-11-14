package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runaxsoftware.qdrant.jdbc.schema.DriverColumns;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AddColumnStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ColumnSpec;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropColumnStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.AddVectorPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropVectorPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Plans ALTER TABLE: the columns it adds and drops are named vectors, the only columns Qdrant
 * keeps a declaration of. A payload field has none to add or drop, so those are errors that
 * name the statement that does what was meant. Whether the vector is there is the executor's
 * to look up, since the columns the driver keeps may be older than the collection.
 */
final class AlterPlanner {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    // The unnamed vector, which a collection gets when it's created or never
    private static final String UNNAMED = DriverColumns.VECTOR.columnName();

    private static final String VERB = "ALTER";

    Plan plan(AddColumnStatement statement) throws SQLException {
        String collection = WritePlanner.collection(statement.table(), VERB);
        ColumnSpec column = statement.column();

        if (column.name().equals(UNNAMED)) {
            throw new SQLFeatureNotSupportedException("Can't add " + UNNAMED + " to "
                + collection + ": a collection has its unnamed vector from CREATE TABLE or not "
                + "at all, and Qdrant adds only named ones. Give the vector a name of its own");
        }

        ObjectNode config = JSON.objectNode();

        switch (column.type()) {
            case "VECTOR", "MULTIVECTOR" ->
                config.set("dense", CreatePlanner.dense(column, collection));
            case "SPARSE_VECTOR" -> config.set("sparse", CreatePlanner.sparse(column));
            default -> throw payloadColumn(column, collection);
        }

        return new AddVectorPlan(collection, column.name(), config, statement.ifNotExists());
    }

    Plan plan(DropColumnStatement statement) throws SQLException {
        String collection = WritePlanner.collection(statement.table(), VERB);

        if (statement.column().equals(UNNAMED)) {
            throw new SQLFeatureNotSupportedException("Can't drop " + UNNAMED + " of "
                + collection + ": Qdrant deletes only named vectors. UPDATE " + collection
                + " SET " + UNNAMED + " = NULL WHERE ... takes it off the points instead");
        }

        return new DropVectorPlan(collection, statement.column(), statement.ifExists());
    }

    // A payload column: Qdrant's payload has no schema, so the only thing to add is an index
    private static SQLException payloadColumn(ColumnSpec column, String collection) {
        String index = CreatePlanner.indexType(column.type());

        if (index == null) {
            return new SQLFeatureNotSupportedException("Unknown column type '" + column.type()
                + "' for '" + column.name() + "': ALTER TABLE adds vectors, as VECTOR(n), "
                + "MULTIVECTOR(n) or SPARSE_VECTOR");
        }

        return new SQLFeatureNotSupportedException("Payload fields need no adding, since "
            + "Qdrant's payload has no schema: a point has '" + column.name() + "' once an "
            + "INSERT or UPDATE gives it one. To index the field, CREATE INDEX ON " + collection
            + " (" + column.name() + ") USING " + index);
    }
}
