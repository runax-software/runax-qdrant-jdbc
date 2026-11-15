package com.runaxsoftware.qdrant.jdbc.statement;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Set;

/**
 * How many parameters a prepared statement takes. Most types aren't known until values are
 * given, since a payload field holds whatever its points hold, so each is reported as any type.
 * A LIMIT or OFFSET takes a whole number, so its placeholder is reported as a {@code BIGINT}.
 */
final class QdrantParameterMetaData implements ParameterMetaData {

    private final int count;
    private final Set<Integer> rowCounts;

    /**
     * @param rowCounts the numbers of the parameters that take a row count
     */
    QdrantParameterMetaData(int count, Set<Integer> rowCounts) {
        this.count = count;
        this.rowCounts = Set.copyOf(rowCounts);
    }

    @Override
    public int getParameterCount() {
        return count;
    }

    @Override
    public int isNullable(int param) throws SQLException {
        check(param);
        return rowCounts.contains(param) ? parameterNoNulls : parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        check(param);
        return rowCounts.contains(param);
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        check(param);
        return rowCounts.contains(param) ? 19 : 0;
    }

    @Override
    public int getScale(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        check(param);
        return rowCounts.contains(param) ? Types.BIGINT : Types.OTHER;
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        check(param);
        return rowCounts.contains(param) ? "BIGINT" : "OTHER";
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        check(param);
        return (rowCounts.contains(param) ? Long.class : Object.class).getName();
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        check(param);
        return parameterModeIn;
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) return type.cast(this);
        throw new SQLException("Not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }

    private void check(int param) throws SQLException {
        QdrantPreparedStatement.checkIndex(param, count);
    }
}
