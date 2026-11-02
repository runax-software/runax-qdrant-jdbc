package com.runaxsoftware.qdrant.jdbc.sql.planner;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Star;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.AlterViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.CreateViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.DropViewStatement;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement.SelectStatement;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.CreateViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.DropViewPlan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.RenameViewPlan;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;

/**
 * Plans the statements about views, which are Qdrant aliases: another name for a whole
 * collection. So a view is {@code SELECT * FROM} one collection and nothing else, which is
 * also what lets {@code CREATE OR REPLACE VIEW} switch every reader to a new collection at once.
 */
final class ViewPlanner {

    private static final String SHAPE = "A view is a Qdrant alias, another name for a whole "
        + "collection, so it's written CREATE VIEW current AS SELECT * FROM docs";

    Plan plan(CreateViewStatement statement, Catalog catalog) throws SQLException {
        String view = WritePlanner.collection(statement.view(), "CREATE VIEW");
        SelectStatement query = statement.query();

        if (query.table() == null || !query.groupBy().isEmpty() || !query.orderBy().isEmpty()
            || query.where() != null || query.limit() != null || query.offset() != null
            || query.items().size() != 1
            || !(query.items().get(0).expression() instanceof Star star)) {
            throw new SQLFeatureNotSupportedException(SHAPE);
        }

        String collection = WritePlanner.collection(query.table(), "show in a view");
        String table = query.alias() != null ? query.alias() : collection;

        if (star.qualifier() != null && !star.qualifier().equals(table)) {
            throw new SQLSyntaxErrorException("Unknown table or alias '" + star.qualifier()
                + "' in '" + star.qualified() + "': the view shows " + collection, "42S02");
        }

        // Reports a missing collection now, before Qdrant is asked to point at it
        catalog.table(collection);

        if (collection.equals(view)) {
            throw new SQLSyntaxErrorException("A view can't have its collection's name", "42S01");
        }

        return new CreateViewPlan(view, collection, statement.orReplace());
    }

    Plan plan(AlterViewStatement statement) throws SQLException {
        String view = WritePlanner.collection(statement.view(), "ALTER VIEW");

        if (view.equals(statement.newName())) {
            throw new SQLSyntaxErrorException("View '" + view + "' already has that name", "42000");
        }

        return new RenameViewPlan(view, statement.newName());
    }

    Plan plan(DropViewStatement statement) throws SQLException {
        return new DropViewPlan(WritePlanner.collection(statement.view(), "DROP VIEW"),
            statement.ifExists());
    }
}
