package com.runaxsoftware.qdrant.jdbc.result;

import java.sql.JDBCType;
import java.sql.ResultSetMetaData;
import java.sql.Types;

/**
 * One column of a result set.
 *
 * @param name      the column name and label
 * @param sqlType   the {@link Types} constant
 * @param typeName  the type name shown to users, such as {@code "BIGINT"} or {@code "JSON"}
 * @param tableName the collection the column comes from, or an empty string
 * @param precision the column size (such as a vector's dimensions), or 0 when unknown
 * @param nullable  one of the {@link ResultSetMetaData} {@code column...} nullability constants
 */
public record ResultColumn(
    String name,
    int sqlType,
    String typeName,
    String tableName,
    int precision,
    int nullable) {

    /**
     * A nullable column of a standard JDBC type, named after that type.
     */
    public static ResultColumn of(String name, int sqlType) {
        return new ResultColumn(name, sqlType, JDBCType.valueOf(sqlType).getName(), "", 0,
            ResultSetMetaData.columnNullable);
    }

    /**
     * A {@code VARCHAR} column.
     */
    public static ResultColumn text(String name) {
        return of(name, Types.VARCHAR);
    }

    /**
     * An {@code INTEGER} column.
     */
    public static ResultColumn integer(String name) {
        return of(name, Types.INTEGER);
    }

    /**
     * A {@code SMALLINT} column.
     */
    public static ResultColumn smallint(String name) {
        return of(name, Types.SMALLINT);
    }

    /**
     * A {@code BIGINT} column.
     */
    public static ResultColumn bigint(String name) {
        return of(name, Types.BIGINT);
    }

    /**
     * A {@code BOOLEAN} column.
     */
    public static ResultColumn bool(String name) {
        return of(name, Types.BOOLEAN);
    }
}
