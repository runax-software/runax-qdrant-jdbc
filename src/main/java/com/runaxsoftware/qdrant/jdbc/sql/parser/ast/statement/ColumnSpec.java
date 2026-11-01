package com.runaxsoftware.qdrant.jdbc.sql.parser.ast.statement;

/**
 * One column of a CREATE TABLE, as written: which of these mean anything for the column's type
 * is the planner's to say.
 *
 * @param name     the column name
 * @param type     the type, upper case, as {@code VECTOR}, {@code SPARSE_VECTOR} or
 *                 {@code KEYWORD}
 * @param size     the number in brackets after the type, as in {@code VECTOR(384)}, or
 *                 {@code null}
 * @param distance the word after DISTANCE, upper case, or {@code null}
 * @param modifier the word after MODIFIER, upper case, or {@code null}
 * @param index    whether the column ends with INDEX
 */
public record ColumnSpec(String name, String type, Integer size, String distance,
                         String modifier, boolean index) {}
