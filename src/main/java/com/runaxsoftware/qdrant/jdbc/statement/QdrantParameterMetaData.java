package com.runaxsoftware.qdrant.jdbc.statement;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * How many parameters a prepared statement takes. Their types aren't known until values are
 * given, since a payload field holds whatever its points hold, so each is reported as any type.
 */
final class QdrantParameterMetaData implements ParameterMetaData {

    private final int count;

    QdrantParameterMetaData(int count) {
        this.count = count;
    }

    @Override
    public int getParameterCount() {
        return count;
    }

    @Override
    public int isNullable(int param) throws SQLException {
        check(param);
        return parameterNullableUnknown;
    }

    @Override
    public boolean isSigned(int param) throws SQLException {
        check(param);
        return false;
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getScale(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        check(param);
        return Types.OTHER;
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        check(param);
        return "OTHER";
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        check(param);
        return Object.class.getName();
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
