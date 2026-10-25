package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

import com.runaxsoftware.qdrant.jdbc.result.ResultColumn;

import java.util.List;

/**
 * Rows the driver works out itself, without calling Qdrant, such as
 * {@code SELECT 'keep alive'}.
 *
 * @param columns the result set's columns
 * @param rows    the rows, each with one value per column
 */
public record ConstantPlan(List<ResultColumn> columns, List<List<Object>> rows) implements Plan {}
