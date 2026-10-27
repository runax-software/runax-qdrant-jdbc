package com.runaxsoftware.qdrant.jdbc.resultset;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * A result set over rows held in memory, used for metadata.
 */
public final class ListResultSet extends AbstractResultSet {

    private final List<Object[]> rows;
    private int index = -1;

    /**
     * @param columns   the columns, in order
     * @param rows      the rows; each array holds one value per column, in column order
     * @param statement the producing statement, or {@code null} for metadata
     */
    public ListResultSet(List<ResultColumn> columns, List<Object[]> rows, Statement statement) {
        super(columns, statement);

        for (Object[] row : rows) {
            if (row.length != columns.size()) {
                throw new IllegalArgumentException(
                    "Row has " + row.length + " values for " + columns.size() + " columns");
            }
        }

        this.rows = List.copyOf(rows);
    }

    /**
     * A metadata result set with the given columns and no rows.
     */
    public static ListResultSet empty(List<ResultColumn> columns) {
        return new ListResultSet(columns, List.of(), null);
    }

    @Override
    protected boolean advance() {
        if (index + 1 < rows.size()) {
            index++;
            return true;
        }

        index = rows.size();
        return false;
    }

    @Override
    protected boolean hasNextRow() {
        return index + 1 < rows.size();
    }

    @Override
    protected Object currentValue(int columnIndex) throws SQLException {
        return rows.get(index)[columnIndex - 1];
    }
}
