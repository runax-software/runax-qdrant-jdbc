package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.TableName;

/**
 * {@code ALTER VIEW current RENAME TO live}: an alias under a new name.
 *
 * @param view    the view's name
 * @param newName the name it takes
 */
public record AlterViewStatement(TableName view, String newName) implements Statement {}
