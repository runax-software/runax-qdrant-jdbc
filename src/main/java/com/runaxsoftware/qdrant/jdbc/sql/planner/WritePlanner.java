package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DeleteStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.TruncateStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ClearIssuesPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DeletePointsPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropCollectionPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.system.SystemTable;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Plans the statements that change what Qdrant holds: DELETE, TRUNCATE, DROP TABLE, and
 * CREATE TABLE. The {@code system} schema's tables are read-only, but for TRUNCATE of
 * {@code system.issues}.
 */
final class WritePlanner {

    /**
     * Deletes the points the WHERE matches. A DELETE without WHERE would empty the collection,
     * which is easy to send by mistake, so that takes TRUNCATE TABLE, said on purpose.
     */
    Plan plan(DeleteStatement statement, Catalog catalog) throws SQLException {
        TableSchema schema = catalog.table(collection(statement.table(), "DELETE from"));

        if (statement.where() == null) {
            throw new SQLFeatureNotSupportedException("DELETE without WHERE would delete every "
                + "point of " + schema.name() + ": to do that, use TRUNCATE TABLE "
                + schema.name());
        }

        return new DeletePointsPlan(schema.name(), FilterPlanner.plan(statement.where(),
            schema,
            column -> SelectPlanner.find(schema, column, statement.alias(), false)));
    }

    /**
     * Deletes every point of a collection, or clears {@code system.issues}: the one system table
     * Qdrant lets a client empty, and only all at once, which is what TRUNCATE says.
     */
    Plan plan(TruncateStatement statement, Catalog catalog) throws SQLException {
        if (isIssues(statement.table())) {
            SelectPlanner.checkCatalog(statement.table());
            return new ClearIssuesPlan();
        }

        TableSchema schema = catalog.table(collection(statement.table(), "TRUNCATE"));

        return new DeletePointsPlan(schema.name(), JsonNodeFactory.instance.objectNode());
    }

    /**
     * Deletes the collection. Its columns aren't needed, so the catalog isn't asked, and a
     * missing collection is Qdrant's to report, which IF EXISTS turns into nothing to do.
     */
    Plan plan(DropTableStatement statement) throws SQLException {
        return new DropCollectionPlan(collection(statement.table(), "DROP"),
            statement.ifExists());
    }

    /**
     * Creates the collection. Its columns aren't read: the statement says what they are.
     */
    Plan plan(CreateTableStatement statement) throws SQLException {
        return CreatePlanner.plan(statement, collection(statement.table(), "CREATE"));
    }

    // The collection a statement names; the system schema's tables are read-only
    static String collection(TableName table, String verb) throws SQLException {
        SelectPlanner.checkCatalog(table);

        if (isIssues(table)) {
            throw new SQLFeatureNotSupportedException("Can't " + verb + " " + table.qualified()
                + ": Qdrant clears its issues all at once, so use TRUNCATE TABLE "
                + SystemTable.SCHEMA + "." + SystemTable.ISSUES.tableName());
        }

        if (SystemTable.isSystemSchema(table.schema())) {
            throw new SQLFeatureNotSupportedException("Can't " + verb + " "
                + table.qualified() + ": the " + SystemTable.SCHEMA + " schema's tables are "
                + "read-only");
        }

        SelectPlanner.checkCollectionSchema(table);
        return table.name();
    }

    private static boolean isIssues(TableName table) {
        return SystemTable.isSystemSchema(table.schema())
            && SystemTable.named(table.name()).filter(SystemTable.ISSUES::equals).isPresent();
    }
}
