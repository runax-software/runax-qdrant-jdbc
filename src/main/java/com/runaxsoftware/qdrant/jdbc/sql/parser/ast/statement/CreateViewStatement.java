package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

/**
 * A CREATE VIEW, which is a Qdrant alias: {@code CREATE VIEW current AS SELECT * FROM docs_v2}.
 * Which queries a view may hold is the planner's to say.
 *
 * @param view      the view's name
 * @param query     the SELECT after AS
 * @param orReplace whether an existing view by that name moves to the new collection
 */
public record CreateViewStatement(TableName view, SelectStatement query, boolean orReplace)
    implements Statement {}
