package com.runaxsoftware.qdrant.jdbc.system;

import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;

/**
 * One column of a system table. Unlike a collection's columns, which are worked out from the
 * collection, these are fixed: the driver knows them without calling Qdrant.
 *
 * @param name    the column name, which an alias replaces
 * @param type    the SQL type
 * @param source  the table's own name for the column, which says where its values come from
 *                and so stays the same under an alias
 * @param remarks a description shown as the column comment
 */
public record SystemColumn(String name, ColumnType type, String source, String remarks) {

    /**
     * A column named as the table names it.
     */
    static SystemColumn of(String name, ColumnType type, String remarks) {
        return new SystemColumn(name, type, name, remarks);
    }

    /**
     * The same column under another name, for {@code SELECT name AS collection}.
     */
    public SystemColumn as(String alias) {
        return new SystemColumn(alias, type, source, remarks);
    }

    /**
     * Whether the column can hold nulls. Every system column can: Qdrant leaves fields out of
     * its answers, such as the counts of a collection that isn't ready yet.
     */
    public boolean nullable() {
        return true;
    }
}
