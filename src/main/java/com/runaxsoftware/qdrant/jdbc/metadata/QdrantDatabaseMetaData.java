package com.runaxsoftware.qdrant.jdbc.metadata;

import com.runaxsoftware.qdrant.jdbc.DriverInfo;
import com.runaxsoftware.qdrant.jdbc.connection.QdrantConnection;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.DriverColumns;
import com.runaxsoftware.qdrant.jdbc.schema.SchemaInference;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Describes a Qdrant server to JDBC tools.
 *
 * <p>Each collection is a table. There are no catalogs or schemas, so tables are reported with
 * {@code null} for both, and catalog and schema filters other than empty or {@code %} match
 * nothing. Columns come from {@link SchemaInference}: every table has an {@code _id} primary
 * key, and payload indexes show up as table indexes. Everything is read live from Qdrant.
 */
public final class QdrantDatabaseMetaData extends AbstractDatabaseMetaData {
    private static final String TABLE_TYPE = "TABLE";
    private static final String PRIMARY_KEY_SUFFIX = "_pkey";
    private static final String INDEX_SUFFIX = "_idx";

    private final QdrantConnection connection;

    public QdrantDatabaseMetaData(QdrantConnection connection) {
        this.connection = connection;
    }

    @Override
    void checkOpen() throws SQLException {
        connection.checkOpen();
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        checkOpen();
        List<Object[]> rows = new ArrayList<>();

        if (inScope(catalog, schemaPattern) && includesTables(types)) {
            for (String name : sortedCollections(tableNamePattern)) {
                rows.add(new Object[]{null, null, name, TABLE_TYPE, null, null, null, null, null, null});
            }
        }

        return MetadataColumns.TABLES.rows(rows);
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        checkOpen();

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{TABLE_TYPE});

        return MetadataColumns.TABLE_TYPES.rows(rows);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        checkOpen();
        List<Object[]> rows = new ArrayList<>();

        if (inScope(catalog, schemaPattern)) {
            for (String name : sortedCollections(tableNamePattern)) {
                TableSchema schema = readSchema(name);
                if (schema == null) continue;

                List<ColumnDefinition> columns = schema.columns();

                for (int i = 0; i < columns.size(); i++) {
                    ColumnDefinition column = columns.get(i);

                    if (LikePattern.matches(columnNamePattern, column.name())) {
                        rows.add(columnRow(name, column, i + 1));
                    }
                }
            }
        }

        return MetadataColumns.COLUMNS.rows(rows);
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        checkOpen();
        List<Object[]> rows = new ArrayList<>();

        if (inScope(catalog, schema) && connection.gateway().listCollections().contains(table)) {
            rows.add(new Object[]{null, null, table, DriverColumns.ID.columnName(), (short) 1, table + PRIMARY_KEY_SUFFIX});
        }

        return MetadataColumns.PRIMARY_KEYS.rows(rows);
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        checkOpen();

        List<ColumnType> types = new ArrayList<>(Arrays.asList(ColumnType.values()));
        types.sort((left, right) -> Integer.compare(left.sqlType(), right.sqlType()));
        List<Object[]> rows = new ArrayList<>();

        for (ColumnType type : types) rows.add(typeInfoRow(type));

        return MetadataColumns.TYPE_INFO.rows(rows);
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        checkOpen();
        List<Object[]> rows = new ArrayList<>();
        TableSchema tableSchema = inScope(catalog, schema) ? readSchema(table) : null;

        if (tableSchema != null) {
            rows.add(new Object[]{null, null, table, false, null, table + PRIMARY_KEY_SUFFIX, tableIndexOther, (short) 1, DriverColumns.ID.columnName(), null, tableSchema.collection().pointsCount(), null, null});

            if (!unique) {
                List<String> indexed = new ArrayList<>(tableSchema.collection().payloadIndexes().keySet());
                indexed.sort(null);

                for (String path : indexed) {
                    if (tableSchema.column(path).isPresent()) {
                        rows.add(new Object[]{null, null, table, true, null, path + INDEX_SUFFIX, tableIndexOther, (short) 1, path, null, null, null, null});
                    }
                }
            }
        }

        return MetadataColumns.INDEX_INFO.rows(rows);
    }

    @Override
    public String getDatabaseProductName() {
        return "Qdrant";
    }

    @Override
    public String getDatabaseProductVersion() {
        return connection.serverVersion().version();
    }

    @Override
    public int getDatabaseMajorVersion() {
        return connection.serverVersion().major();
    }

    @Override
    public int getDatabaseMinorVersion() {
        return connection.serverVersion().minor();
    }

    @Override
    public String getDriverName() {
        return DriverInfo.NAME;
    }

    @Override
    public String getDriverVersion() {
        return DriverInfo.VERSION;
    }

    @Override
    public int getDriverMajorVersion() {
        return DriverInfo.MAJOR_VERSION;
    }

    @Override
    public int getDriverMinorVersion() {
        return DriverInfo.MINOR_VERSION;
    }

    @Override
    public String getURL() {
        return connection.url();
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    /**
     * There are no catalogs or schemas: accept only filters that don't ask for one.
     */
    private static boolean inScope(String catalog, String schemaPattern) {
        return (catalog == null || catalog.isEmpty()) && (schemaPattern == null || schemaPattern.isEmpty()
            || schemaPattern.equals("%"));
    }

    private static boolean includesTables(String[] types) {
        if (types == null) return true;
        for (String type : types) if (TABLE_TYPE.equalsIgnoreCase(type)) return true;

        return false;
    }

    private List<String> sortedCollections(String tableNamePattern) throws SQLException {
        List<String> names = new ArrayList<>();

        for (String name : connection.gateway().listCollections()) {
            if (LikePattern.matches(tableNamePattern, name)) {
                names.add(name);
            }
        }

        names.sort(null);
        return names;
    }

    /**
     * Reads a collection's schema, or {@code null} if the collection no longer exists.
     */
    private TableSchema readSchema(String collection) throws SQLException {
        try {
            return connection.schemaReader().read(collection);
        } catch (SQLException e) {
            if ("42S02".equals(e.getSQLState())) {
                return null;
            }

            throw e;
        }
    }

    private static Object[] columnRow(String table, ColumnDefinition column, int position) {
        ColumnType type = column.type();
        // Integer.valueOf keeps the conditional boxed: numericPrecision is null for non-numbers.
        Integer size = column.kind() == ColumnKind.VECTOR && column.size() > 0
            ? Integer.valueOf(column.size())
            : numericPrecision(type);
        Integer radix = numericPrecision(type) == null ? null : 10;

        return new Object[]{null, null, table, column.name(), type.sqlType(), type.typeName(), size, null, null, radix, column.nullable() ? columnNullable : columnNoNulls, column.remarks(), null, null, null, null, position, column.nullable() ? "YES" : "NO", null, null, null, null, "NO", "NO",};
    }

    private static Object[] typeInfoRow(ColumnType type) {
        boolean quoted = type.sqlType() == Types.VARCHAR || type.sqlType() == Types.TIMESTAMP_WITH_TIMEZONE;
        boolean vector = type == ColumnType.VECTOR || type == ColumnType.MULTIVECTOR || type == ColumnType.SPARSE_VECTOR;

        Integer precision = numericPrecision(type);
        short searchable = vector ? (short) typePredNone : (short) typeSearchable;

        return new Object[]{type.typeName(), type.sqlType(), precision, quoted ? "'" : null, quoted ? "'" : null, null, (short) typeNullable, type.sqlType() == Types.VARCHAR, searchable, false, false, false, type.typeName(), (short) 0, (short) 0, null, null, precision == null ? null : 10,};
    }

    private static Integer numericPrecision(ColumnType type) {
        return switch (type) {
            case INTEGER -> 19;
            case FLOAT -> 17;
            default -> null;
        };
    }
}
