package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Expression;
import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

/**
 * A DELETE of the points a condition matches.
 *
 * @param table the collection to delete points from
 * @param alias the name the statement gives that collection, as in {@code DELETE FROM docs t},
 *              or {@code null}
 * @param where the WHERE condition, or {@code null}
 */
public record DeleteStatement(TableName table, String alias, Expression where)
    implements Statement {}
