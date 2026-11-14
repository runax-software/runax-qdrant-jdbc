package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

import com.runaxsoftware.qdrant.jdbc.sql.parser.ast.expression.Column;

/**
 * The best rows of each group a SELECT keeps, as {@code LIMIT 3 BY doc_id} or
 * {@code DISTINCT ON (doc_id)} says it.
 *
 * @param column the column whose values are the groups
 * @param size   how many rows of each group to keep; 1 for DISTINCT ON
 */
public record PerGroup(Column column, long size) {}
