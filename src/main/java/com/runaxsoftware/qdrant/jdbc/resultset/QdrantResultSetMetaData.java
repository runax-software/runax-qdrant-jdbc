package com.runaxsoftware.qdrant.jdbc.resultset;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * {@link ResultSetMetaData} for a fixed list of {@link ResultColumn}s.
 */
final class QdrantResultSetMetaData implements ResultSetMetaData {

    private final List<ResultColumn> columns;

    /**
     * Describes the given columns, in order.
     */
    QdrantResultSetMetaData(List<ResultColumn> columns) {
        this.columns = List.copyOf(columns);
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        column(column);
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return column(column).sqlType() == Types.VARCHAR;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        column(column);
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        column(column);
        return false;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return column(column).nullable();
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return switch (column(column).sqlType()) {
            case Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.DOUBLE, Types.DECIMAL -> true;
            default -> false;
        };
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        ResultColumn result = column(column);
        if (result.precision() > 0) {
            return result.precision();
        }

        return switch (result.sqlType()) {
            case Types.BOOLEAN -> 5;
            case Types.SMALLINT -> 6;
            case Types.INTEGER -> 11;
            case Types.BIGINT -> 20;
            case Types.DOUBLE -> 24;
            case Types.TIMESTAMP_WITH_TIMEZONE -> 35;
            default -> 255;
        };
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return column(column).name();
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        return column(column).name();
    }

    @Override
    public String getSchemaName(int column) throws SQLException {
        column(column);
        return "";
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return column(column).precision();
    }

    @Override
    public int getScale(int column) throws SQLException {
        column(column);
        return 0;
    }

    @Override
    public String getTableName(int column) throws SQLException {
        return column(column).tableName();
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        column(column);
        return "";
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return column(column).sqlType();
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return column(column).typeName();
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        column(column);
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        column(column);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        column(column);
        return false;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return switch (column(column).sqlType()) {
            case Types.BOOLEAN -> Boolean.class.getName();
            case Types.SMALLINT -> Short.class.getName();
            case Types.INTEGER -> Integer.class.getName();
            case Types.BIGINT -> Long.class.getName();
            case Types.DOUBLE -> Double.class.getName();
            case Types.TIMESTAMP_WITH_TIMEZONE -> java.time.OffsetDateTime.class.getName();
            case Types.VARCHAR -> String.class.getName();
            default -> Object.class.getName();
        };
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }

        throw new SQLException("Not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }

    private ResultColumn column(int column) throws SQLException {
        if (column < 1 || column > columns.size()) {
            throw new SQLException(
                "Column index " + column + " is out of range 1.." + columns.size(), "07009");
        }

        return columns.get(column - 1);
    }
}
