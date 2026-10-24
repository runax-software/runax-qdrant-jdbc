package com.runaxsoftware.qdrant.jdbc.schema;

import com.runaxsoftware.qdrant.jdbc.client.CollectionInfo;

import java.util.List;
import java.util.Optional;

/**
 * A collection seen as a table.
 *
 * @param collection the collection's configuration and indexes
 * @param columns    the columns, in order
 */
public record TableSchema(CollectionInfo collection, List<ColumnDefinition> columns) {

    /**
     * The table name, which is the collection name.
     */
    public String name() {
        return collection.name();
    }

    /**
     * Finds a column by its exact name.
     */
    public Optional<ColumnDefinition> column(String name) {
        return columns.stream().filter(column -> column.name().equals(name)).findFirst();
    }
}
