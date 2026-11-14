package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AddColumnStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AlterViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateIndexStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DeleteStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropColumnStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropIndexStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropTableStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropViewStatement;
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

    /**
     * The deepest OFFSET a read the driver skips itself will go. Qdrant has no row offset for a
     * scroll or a facet, so the only way to page one by a row count is to read the first rows and
     * throw them away: that work grows with the offset while the statement returns the same few
     * rows, and past some point a statement that looks like it reads one screen reads a whole
     * collection instead, which is the one thing this driver doesn't do quietly. A data grid's
     * screen is a few hundred rows, so this is deep enough to page through one by hand and
     * shallow enough to stay the "small, bounded work" the dialect spec allows. A search's offset
     * goes to Qdrant and a system table is in memory already, so neither of those is capped.
     */
    public static final long MAX_OFFSET = 10_000;

    private final SelectPlanner selectPlanner = new SelectPlanner();
    private final WritePlanner writePlanner = new WritePlanner();
    private final OptionPlanner optionPlanner = new OptionPlanner();
    private final UpdatePlanner updatePlanner = new UpdatePlanner();
    private final InsertPlanner insertPlanner = new InsertPlanner();
    private final IndexPlanner indexPlanner = new IndexPlanner();
    private final ViewPlanner viewPlanner = new ViewPlanner();
    private final AlterPlanner alterPlanner = new AlterPlanner();

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
        if (statement instanceof CreateIndexStatement create) {
            return indexPlanner.plan(create, catalog);
        }
        if (statement instanceof DropIndexStatement drop) {
            return indexPlanner.plan(drop, catalog);
        }
        if (statement instanceof CreateViewStatement create) {
            return viewPlanner.plan(create, catalog);
        }
        if (statement instanceof AlterViewStatement alter) {
            return viewPlanner.plan(alter);
        }
        if (statement instanceof DropViewStatement drop) {
            return viewPlanner.plan(drop);
        }
        if (statement instanceof AddColumnStatement add) {
            return alterPlanner.plan(add);
        }
        if (statement instanceof DropColumnStatement drop) {
            return alterPlanner.plan(drop);
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
