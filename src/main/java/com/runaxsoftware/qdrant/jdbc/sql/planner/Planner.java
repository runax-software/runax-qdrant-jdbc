package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DeleteStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.InsertStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ResetStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SetStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.ShowStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.Statement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.TruncateStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.UpdateStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * Turns a parsed statement into a plan, picking the planner for its kind of statement.
 */
public final class Planner {

    private final SelectPlanner selectPlanner = new SelectPlanner();
    private final WritePlanner writePlanner = new WritePlanner();
    private final OptionPlanner optionPlanner = new OptionPlanner();
    private final UpdatePlanner updatePlanner = new UpdatePlanner();
    private final InsertPlanner insertPlanner = new InsertPlanner();

    public Plan plan(Statement statement, Catalog catalog) throws SQLException {
        if (statement instanceof SelectStatement select) {
            return selectPlanner.plan(select, catalog);
        }
        if (statement instanceof DeleteStatement delete) {
            return writePlanner.plan(delete, catalog);
        }
        if (statement instanceof TruncateStatement truncate) {
            return writePlanner.plan(truncate, catalog);
        }
        if (statement instanceof DropTableStatement drop) {
            return writePlanner.plan(drop);
        }
        if (statement instanceof CreateTableStatement create) {
            return writePlanner.plan(create);
        }
        if (statement instanceof InsertStatement insert) {
            return insertPlanner.plan(insert, catalog);
        }
        if (statement instanceof UpdateStatement update) {
            return updatePlanner.plan(update, catalog);
        }
        if (statement instanceof SetStatement set) {
            return optionPlanner.plan(set);
        }
        if (statement instanceof ResetStatement reset) {
            return optionPlanner.plan(reset);
        }
        if (statement instanceof ShowStatement show) {
            return optionPlanner.plan(show);
        }

        throw new SQLFeatureNotSupportedException(
            statement.getClass().getSimpleName() + " isn't supported yet");
    }
}
