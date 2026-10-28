package com.runaxsoftware.qdrant.jdbc.metadata;

import com.runaxsoftware.qdrant.jdbc.DriverInfo;
import com.runaxsoftware.qdrant.jdbc.connection.QdrantConnection;
import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantAlias;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.DriverColumns;
import com.runaxsoftware.qdrant.jdbc.schema.SchemaInference;
import com.runaxsoftware.qdrant.jdbc.schema.SchemaReader;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Describes a Qdrant server to JDBC tools.
 *
 * <p>Each collection is a table and each alias a view. Qdrant has no catalogs or schemas, but
 * tools such as DataGrip show both levels anyway, as unnamed placeholders when a driver reports
 * none. So every table and view sits in one catalog, {@value #CATALOG}, and one schema,
 * {@value #SCHEMA}. Catalog and schema filters that name anything else match nothing. Columns
 * come from {@link SchemaInference}: every table has an {@code _id} primary key, and payload
 * indexes show up as table indexes.
 *
 * <p>A view has the columns of the collection its alias points at, under the view's name, and
 * its remarks name that collection. It has no primary key and no indexes, like a view or an
 * alias in other databases, so a tool shows the key and indexes once, on the collection; the
 * view's column remarks still say which fields are indexed.
 *
 * <p>{@link #getTables} reads the collection and alias lists from Qdrant on every call, and the
 * connection's {@link SchemaReader} keeps them; columns, keys, and indexes use the kept lists,
 * and list again only when none is kept, the kept ones have expired with the connection's
 * {@code schemaCache} setting, or they lack a name asked for (see below). Each collection's
 * columns and indexes come from the same reader, which keeps them for as long. A view's
 * columns are read under its collection's name, so a collection and its aliases share one
 * read. {@link #getTables} makes the reader forget the names it matches and the collections
 * behind the views it lists. Tools list the tables first when they refresh, then ask for each
 * table's columns and indexes, so a refresh lists the collections and aliases once, reads each
 * collection from Qdrant once, and shows its current columns.
 *
 * <p>Between listings, columns, keys, and indexes follow the kept lists, with the rule
 * statements use for a column they don't know: a table or view asked for by its exact name
 * that the kept lists don't have, as for one created since the last listing, makes the reader
 * list both again, once, before it counts as missing. An exact name is any table name of
 * {@link #getPrimaryKeys} and {@link #getIndexInfo}, and a {@link #getColumns} table pattern
 * without an unescaped {@code %} or {@code _}; other patterns use the kept lists as they are.
 * So only such a miss costs a listing. A collection or alias deleted or moved since the last
 * listing shows as it was listed until the next listing or until the kept lists expire.
 */
public final class QdrantDatabaseMetaData extends AbstractDatabaseMetaData {
    /**
     * The one catalog, which every collection and alias is in.
     */
    public static final String CATALOG = "qdrant";

    /**
     * The one schema, which every collection and alias is in.
     */
    public static final String SCHEMA = "collections";

    private static final String TABLE_TYPE = "TABLE";
    private static final String VIEW_TYPE = "VIEW";
    private static final String VIEW_REMARKS = "Alias of ";
    private static final String PRIMARY_KEY_SUFFIX = "_pkey";
    private static final String INDEX_SUFFIX = "_idx";

    /**
     * A table or view: its name, and the collection its columns come from.
     */
    private record Listed(String name, String collection) {
    }

    private final QdrantConnection connection;

    public QdrantDatabaseMetaData(QdrantConnection connection) {
        this.connection = connection;
    }

    @Override
    void checkOpen() throws SQLException {
        connection.checkOpen();
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        checkOpen();

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{CATALOG});

        return MetadataColumns.CATALOGS.rows(rows);
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return getSchemas(null, null);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        checkOpen();
        List<Object[]> rows = new ArrayList<>();

        if (inScope(catalog, schemaPattern)) {
            rows.add(new Object[]{SCHEMA, CATALOG});
        }

        return MetadataColumns.SCHEMAS.rows(rows);
    }

    /**
     * Lists the collections as tables and the aliases as views: tables first, then views, each
     * sorted by name, as JDBC orders them. A view's remarks name its collection. Asks Qdrant only
     * for the kinds the type filter takes in, always afresh, and has the {@link SchemaReader}
     * keep the lists it read for the calls that follow.
     *
     * <p>Makes the reader forget every schema the pattern matches, including ones deleted since
     * it read them, and the collection behind each view it lists, so the columns and indexes
     * asked for next are read again, from the collection an alias points at now.
     */
    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        checkOpen();
        List<Object[]> rows = new ArrayList<>();
        boolean tables = includes(types, TABLE_TYPE);
        boolean views = includes(types, VIEW_TYPE);
        SchemaReader reader = connection.schemaReader();

        if (inScope(catalog, schemaPattern) && (tables || views)) {
            List<String> collections = tables
                ? sortedCollections(tableNamePattern, reader.listCollections()) : List.of();
            List<QdrantAlias> aliases = views
                ? sortedAliases(tableNamePattern, reader.listAliases()) : List.of();
            forgetListed(tableNamePattern, aliases);

            for (String name : collections) rows.add(tableRow(name, TABLE_TYPE, null));
            for (QdrantAlias alias : aliases) {
                rows.add(tableRow(alias.name(), VIEW_TYPE, VIEW_REMARKS + alias.collection()));
            }
        }

        return MetadataColumns.TABLES.rows(rows);
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        checkOpen();

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{TABLE_TYPE});
        rows.add(new Object[]{VIEW_TYPE});

        return MetadataColumns.TABLE_TYPES.rows(rows);
    }

    /**
     * Describes the columns of the tables and views the pattern matches, sorted by their name.
     * A view has the columns of the collection its alias points at. A pattern without
     * wildcards that the kept lists don't have lists them again first.
     */
    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        checkOpen();
        List<Object[]> rows = new ArrayList<>();

        if (inScope(catalog, schemaPattern)) {
            for (Listed table : tablesAndViews(tableNamePattern)) {
                TableSchema schema = readSchema(table.collection());
                if (schema == null) continue;

                List<ColumnDefinition> columns = schema.columns();

                for (int i = 0; i < columns.size(); i++) {
                    ColumnDefinition column = columns.get(i);

                    if (LikePattern.matches(columnNamePattern, column.name())) {
                        rows.add(columnRow(table.name(), column, i + 1));
                    }
                }
            }
        }

        return MetadataColumns.COLUMNS.rows(rows);
    }

    /**
     * Reports {@code _id} as a table's primary key. A view has none. A name the kept lists
     * don't have lists them again first.
     */
    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        checkOpen();
        List<Object[]> rows = new ArrayList<>();

        if (inScope(catalog, schema) && isCollection(table)) {
            rows.add(new Object[]{CATALOG, SCHEMA, table, DriverColumns.ID.columnName(), (short) 1, table + PRIMARY_KEY_SUFFIX});
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

    /**
     * Reports a table's primary key and its payload indexes. A view has none. A name the kept
     * lists don't have lists them again first.
     */
    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        checkOpen();
        List<Object[]> rows = new ArrayList<>();
        TableSchema tableSchema = inScope(catalog, schema) && isCollection(table) ? readSchema(table) : null;

        if (tableSchema != null) {
            rows.add(new Object[]{CATALOG, SCHEMA, table, false, null, table + PRIMARY_KEY_SUFFIX, tableIndexOther, (short) 1, DriverColumns.ID.columnName(), null, tableSchema.collection().pointsCount(), null, null});

            if (!unique) {
                List<String> indexed = new ArrayList<>(tableSchema.collection().payloadIndexes().keySet());
                indexed.sort(null);

                for (String path : indexed) {
                    if (tableSchema.column(path).isPresent()) {
                        rows.add(new Object[]{CATALOG, SCHEMA, table, true, null, path + INDEX_SUFFIX, tableIndexOther, (short) 1, path, null, null, null, null});
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
     * Whether the filters take in the one catalog and schema. {@code null} doesn't filter, and
     * neither does an empty string, which tools also pass to mean "any".
     */
    private static boolean inScope(String catalog, String schemaPattern) {
        return (catalog == null || catalog.isEmpty() || catalog.equals(CATALOG))
            && (schemaPattern == null || schemaPattern.isEmpty()
            || LikePattern.matches(schemaPattern, SCHEMA));
    }

    private static boolean includes(String[] types, String tableType) {
        if (types == null) return true;
        for (String type : types) if (tableType.equalsIgnoreCase(type)) return true;

        return false;
    }

    private static Object[] tableRow(String name, String type, String remarks) {
        return new Object[]{CATALOG, SCHEMA, name, type, remarks, null, null, null, null, null};
    }

    /**
     * Makes the {@link SchemaReader} forget the names the pattern matches, and the collections
     * behind the listed views, whose columns those views show.
     */
    private void forgetListed(String tableNamePattern, List<QdrantAlias> views) {
        Set<String> collections = new HashSet<>();
        for (QdrantAlias view : views) collections.add(view.collection());

        connection.schemaReader().forget(
            name -> LikePattern.matches(tableNamePattern, name) || collections.contains(name));
    }

    // Whether the name is a collection rather than a view or nothing, listing again on a miss
    private boolean isCollection(String name) throws SQLException {
        return name != null && name.equals(connection.schemaReader().collectionOf(name));
    }

    /**
     * The tables and views a pattern matches, sorted by name. A pattern without wildcards names
     * one table or view, which is looked up with {@link SchemaReader#collectionOf}, so one
     * created since the last listing is found; any other pattern is matched against the kept
     * lists.
     */
    private List<Listed> tablesAndViews(String tableNamePattern) throws SQLException {
        String name = LikePattern.literal(tableNamePattern);
        if (name == null) return sortedTablesAndViews(tableNamePattern);

        String collection = connection.schemaReader().collectionOf(name);
        return collection == null ? List.of() : List.of(new Listed(name, collection));
    }

    private static List<String> sortedCollections(String tableNamePattern, List<String> collections) {
        List<String> names = new ArrayList<>();

        for (String name : collections) {
            if (LikePattern.matches(tableNamePattern, name)) {
                names.add(name);
            }
        }

        names.sort(null);
        return names;
    }

    private static List<QdrantAlias> sortedAliases(String tableNamePattern, List<QdrantAlias> all) {
        List<QdrantAlias> aliases = new ArrayList<>();

        for (QdrantAlias alias : all) {
            if (LikePattern.matches(tableNamePattern, alias.name())) {
                aliases.add(alias);
            }
        }

        aliases.sort(Comparator.comparing(QdrantAlias::name));
        return aliases;
    }

    /**
     * The tables and views the pattern matches in the kept lists, sorted by name. Collection
     * and alias names share one namespace in Qdrant, so no name is both.
     */
    private List<Listed> sortedTablesAndViews(String tableNamePattern) throws SQLException {
        SchemaReader reader = connection.schemaReader();
        List<Listed> listed = new ArrayList<>();

        for (String name : sortedCollections(tableNamePattern, reader.collections())) {
            listed.add(new Listed(name, name));
        }

        for (QdrantAlias alias : sortedAliases(tableNamePattern, reader.aliases())) {
            listed.add(new Listed(alias.name(), alias.collection()));
        }

        listed.sort(Comparator.comparing(Listed::name));
        return listed;
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

        return new Object[]{CATALOG, SCHEMA, table, column.name(), type.sqlType(), type.typeName(), size, null, null, radix, column.nullable() ? columnNullable : columnNoNulls, column.remarks(), null, null, null, null, position, column.nullable() ? "YES" : "NO", null, null, null, null, "NO", "NO",};
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
