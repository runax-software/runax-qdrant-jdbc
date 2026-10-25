package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Turns a parsed statement into a plan, picking the planner for its kind of statement.
 */
public final class Planner {

    private final SelectPlanner selectPlanner = new SelectPlanner();

    public Plan plan(Statement statement, Catalog catalog) throws SQLException {
        if (statement instanceof SelectStatement select) {
            return selectPlanner.plan(select, catalog);
        }

        throw new SQLFeatureNotSupportedException(
            statement.getClass().getSimpleName() + " isn't supported yet");
    }
}
