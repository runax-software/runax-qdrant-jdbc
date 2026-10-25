package com.runaxsoftware.qdrant.jdbc;

import static com.runaxsoftware.qdrant.jdbc.result.ResultColumn.bigint;
import static com.runaxsoftware.qdrant.jdbc.result.ResultColumn.bool;
import static com.runaxsoftware.qdrant.jdbc.result.ResultColumn.integer;
import static com.runaxsoftware.qdrant.jdbc.result.ResultColumn.smallint;
import static com.runaxsoftware.qdrant.jdbc.result.ResultColumn.text;

import com.runaxsoftware.qdrant.jdbc.connection.QdrantConnection;
import com.runaxsoftware.qdrant.jdbc.result.ListResultSet;
import com.runaxsoftware.qdrant.jdbc.result.ResultColumn;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.SchemaInference;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Describes a Qdrant server to JDBC tools.
 *
 * <p>Each collection is a table. There are no catalogs or schemas, so tables are reported with
 * {@code null} for both, and catalog and schema filters other than empty or {@code %} match
 * nothing. Columns come from {@link SchemaInference}: every table has an {@code id} primary
 * key, and payload indexes show up as table indexes. Everything is read live from Qdrant.
 */
public final class QdrantDatabaseMetaData implements DatabaseMetaData {

    private static final String TABLE_TYPE = "TABLE";
    private static final String PRIMARY_KEY_SUFFIX = "_pkey";
    private static final String INDEX_SUFFIX = "_idx";

    private static final List<ResultColumn> TABLE_COLUMNS = List.of(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("TABLE_TYPE"), text("REMARKS"), text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("SELF_REFERENCING_COL_NAME"), text("REF_GENERATION"));
    private static final List<ResultColumn> SCHEMA_COLUMNS = List.of(text("TABLE_SCHEM"), text("TABLE_CATALOG"));
    private static final List<ResultColumn> CATALOG_COLUMNS = List.of(text("TABLE_CAT"));
    private static final List<ResultColumn> TABLE_TYPE_COLUMNS = List.of(text("TABLE_TYPE"));
    private static final List<ResultColumn> COLUMN_COLUMNS = List.of(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"), integer("DATA_TYPE"), text("TYPE_NAME"), integer("COLUMN_SIZE"), integer("BUFFER_LENGTH"), integer("DECIMAL_DIGITS"), integer("NUM_PREC_RADIX"), integer("NULLABLE"), text("REMARKS"), text("COLUMN_DEF"), integer("SQL_DATA_TYPE"), integer("SQL_DATETIME_SUB"), integer("CHAR_OCTET_LENGTH"), integer("ORDINAL_POSITION"), text("IS_NULLABLE"), text("SCOPE_CATALOG"), text("SCOPE_SCHEMA"), text("SCOPE_TABLE"), smallint("SOURCE_DATA_TYPE"), text("IS_AUTOINCREMENT"), text("IS_GENERATEDCOLUMN"));
    private static final List<ResultColumn> PRIMARY_KEY_COLUMNS = List.of(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"), smallint("KEY_SEQ"), text("PK_NAME"));
    private static final List<ResultColumn> INDEX_COLUMNS = List.of(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), bool("NON_UNIQUE"), text("INDEX_QUALIFIER"), text("INDEX_NAME"), smallint("TYPE"), smallint("ORDINAL_POSITION"), text("COLUMN_NAME"), text("ASC_OR_DESC"), bigint("CARDINALITY"), bigint("PAGES"), text("FILTER_CONDITION"));
    private static final List<ResultColumn> FOREIGN_KEY_COLUMNS = List.of(text("PKTABLE_CAT"), text("PKTABLE_SCHEM"), text("PKTABLE_NAME"), text("PKCOLUMN_NAME"), text("FKTABLE_CAT"), text("FKTABLE_SCHEM"), text("FKTABLE_NAME"), text("FKCOLUMN_NAME"), smallint("KEY_SEQ"), smallint("UPDATE_RULE"), smallint("DELETE_RULE"), text("FK_NAME"), text("PK_NAME"), smallint("DEFERRABILITY"));
    private static final List<ResultColumn> TYPE_INFO_COLUMNS = List.of(text("TYPE_NAME"), integer("DATA_TYPE"), integer("PRECISION"), text("LITERAL_PREFIX"), text("LITERAL_SUFFIX"), text("CREATE_PARAMS"), smallint("NULLABLE"), bool("CASE_SENSITIVE"), smallint("SEARCHABLE"), bool("UNSIGNED_ATTRIBUTE"), bool("FIXED_PREC_SCALE"), bool("AUTO_INCREMENT"), text("LOCAL_TYPE_NAME"), smallint("MINIMUM_SCALE"), smallint("MAXIMUM_SCALE"), integer("SQL_DATA_TYPE"), integer("SQL_DATETIME_SUB"), integer("NUM_PREC_RADIX"));
    private static final List<ResultColumn> PROCEDURE_COLUMNS = List.of(text("PROCEDURE_CAT"), text("PROCEDURE_SCHEM"), text("PROCEDURE_NAME"), text("RESERVED1"), text("RESERVED2"), text("RESERVED3"), text("REMARKS"), smallint("PROCEDURE_TYPE"), text("SPECIFIC_NAME"));
    private static final List<ResultColumn> PROCEDURE_COLUMN_COLUMNS = List.of(text("PROCEDURE_CAT"), text("PROCEDURE_SCHEM"), text("PROCEDURE_NAME"), text("COLUMN_NAME"), smallint("COLUMN_TYPE"), integer("DATA_TYPE"), text("TYPE_NAME"), integer("PRECISION"), integer("LENGTH"), smallint("SCALE"), smallint("RADIX"), smallint("NULLABLE"), text("REMARKS"), text("COLUMN_DEF"), integer("SQL_DATA_TYPE"), integer("SQL_DATETIME_SUB"), integer("CHAR_OCTET_LENGTH"), integer("ORDINAL_POSITION"), text("IS_NULLABLE"), text("SPECIFIC_NAME"));
    private static final List<ResultColumn> FUNCTION_COLUMNS = List.of(text("FUNCTION_CAT"), text("FUNCTION_SCHEM"), text("FUNCTION_NAME"), text("REMARKS"), smallint("FUNCTION_TYPE"), text("SPECIFIC_NAME"));
    private static final List<ResultColumn> FUNCTION_COLUMN_COLUMNS = List.of(text("FUNCTION_CAT"), text("FUNCTION_SCHEM"), text("FUNCTION_NAME"), text("COLUMN_NAME"), smallint("COLUMN_TYPE"), integer("DATA_TYPE"), text("TYPE_NAME"), integer("PRECISION"), integer("LENGTH"), smallint("SCALE"), smallint("RADIX"), smallint("NULLABLE"), text("REMARKS"), integer("CHAR_OCTET_LENGTH"), integer("ORDINAL_POSITION"), text("IS_NULLABLE"), text("SPECIFIC_NAME"));
    private static final List<ResultColumn> COLUMN_PRIVILEGE_COLUMNS = List.of(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"), text("GRANTOR"), text("GRANTEE"), text("PRIVILEGE"), text("IS_GRANTABLE"));
    private static final List<ResultColumn> TABLE_PRIVILEGE_COLUMNS = List.of(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("GRANTOR"), text("GRANTEE"), text("PRIVILEGE"), text("IS_GRANTABLE"));
    private static final List<ResultColumn> ROW_IDENTIFIER_COLUMNS = List.of(smallint("SCOPE"), text("COLUMN_NAME"), integer("DATA_TYPE"), text("TYPE_NAME"), integer("COLUMN_SIZE"), integer("BUFFER_LENGTH"), smallint("DECIMAL_DIGITS"), smallint("PSEUDO_COLUMN"));
    private static final List<ResultColumn> UDT_COLUMNS = List.of(text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("CLASS_NAME"), integer("DATA_TYPE"), text("REMARKS"), smallint("BASE_TYPE"));
    private static final List<ResultColumn> SUPER_TYPE_COLUMNS = List.of(text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("SUPERTYPE_CAT"), text("SUPERTYPE_SCHEM"), text("SUPERTYPE_NAME"));
    private static final List<ResultColumn> SUPER_TABLE_COLUMNS = List.of(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("SUPERTABLE_NAME"));
    private static final List<ResultColumn> ATTRIBUTE_COLUMNS = List.of(text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("ATTR_NAME"), integer("DATA_TYPE"), text("ATTR_TYPE_NAME"), integer("ATTR_SIZE"), integer("DECIMAL_DIGITS"), integer("NUM_PREC_RADIX"), integer("NULLABLE"), text("REMARKS"), text("ATTR_DEF"), integer("SQL_DATA_TYPE"), integer("SQL_DATETIME_SUB"), integer("CHAR_OCTET_LENGTH"), integer("ORDINAL_POSITION"), text("IS_NULLABLE"), text("SCOPE_CATALOG"), text("SCOPE_SCHEMA"), text("SCOPE_TABLE"), smallint("SOURCE_DATA_TYPE"));
    private static final List<ResultColumn> CLIENT_INFO_COLUMNS = List.of(text("NAME"), integer("MAX_LEN"), text("DEFAULT_VALUE"), text("DESCRIPTION"));
    private static final List<ResultColumn> PSEUDO_COLUMN_COLUMNS = List.of(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"), integer("DATA_TYPE"), integer("COLUMN_SIZE"), integer("DECIMAL_DIGITS"), integer("NUM_PREC_RADIX"), text("COLUMN_USAGE"), text("REMARKS"), integer("CHAR_OCTET_LENGTH"), text("IS_NULLABLE"));

    private final QdrantConnection connection;

    QdrantDatabaseMetaData(QdrantConnection connection) {
        this.connection = connection;
    }

    // Tables and columns

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        connection.checkOpen();
        List<Object[]> rows = new ArrayList<>();

        if (inScope(catalog, schemaPattern) && includesTables(types)) {
            for (String name : sortedCollections(tableNamePattern)) {
                rows.add(new Object[]{null, null, name, TABLE_TYPE, null, null, null, null, null, null});
            }
        }

        return new ListResultSet(TABLE_COLUMNS, rows, null);
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        connection.checkOpen();
        List<Object[]> rows = new ArrayList<>();

        if (inScope(catalog, schemaPattern)) {
            for (String name : sortedCollections(tableNamePattern)) {
                TableSchema schema = readSchema(name);
                if (schema == null) {
                    continue;
                }

                List<ColumnDefinition> columns = schema.columns();

                for (int i = 0; i < columns.size(); i++) {
                    ColumnDefinition column = columns.get(i);

                    if (LikePattern.matches(columnNamePattern, column.name())) {
                        rows.add(columnRow(name, column, i + 1));
                    }
                }
            }
        }

        return new ListResultSet(COLUMN_COLUMNS, rows, null);
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        connection.checkOpen();
        List<Object[]> rows = new ArrayList<>();

        if (inScope(catalog, schema) && connection.gateway().listCollections().contains(table)) {
            rows.add(new Object[]{null, null, table, SchemaInference.ID_COLUMN, (short) 1, table + PRIMARY_KEY_SUFFIX});
        }

        return new ListResultSet(PRIMARY_KEY_COLUMNS, rows, null);
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate) throws SQLException {
        connection.checkOpen();
        List<Object[]> rows = new ArrayList<>();
        TableSchema tableSchema = inScope(catalog, schema) ? readSchema(table) : null;

        if (tableSchema != null) {
            rows.add(new Object[]{null, null, table, false, null, table + PRIMARY_KEY_SUFFIX, tableIndexOther, (short) 1, SchemaInference.ID_COLUMN, null, tableSchema.collection().pointsCount(), null, null});

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

        return new ListResultSet(INDEX_COLUMNS, rows, null);
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        connection.checkOpen();
        return ListResultSet.empty(SCHEMA_COLUMNS);
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        return getSchemas();
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        connection.checkOpen();
        return ListResultSet.empty(CATALOG_COLUMNS);
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        connection.checkOpen();

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{TABLE_TYPE});

        return new ListResultSet(TABLE_TYPE_COLUMNS, rows, null);
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        connection.checkOpen();

        List<ColumnType> types = new ArrayList<>(Arrays.asList(ColumnType.values()));
        types.sort((left, right) -> Integer.compare(left.sqlType(), right.sqlType()));
        List<Object[]> rows = new ArrayList<>();

        for (ColumnType type : types) {
            rows.add(typeInfoRow(type));
        }

        return new ListResultSet(TYPE_INFO_COLUMNS, rows, null);
    }

    // Things Qdrant doesn't have: empty results

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return empty(FOREIGN_KEY_COLUMNS);
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return empty(FOREIGN_KEY_COLUMNS);
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable, String foreignCatalog, String foreignSchema, String foreignTable) throws SQLException {
        return empty(FOREIGN_KEY_COLUMNS);
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws SQLException {
        return empty(PROCEDURE_COLUMNS);
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern) throws SQLException {
        return empty(PROCEDURE_COLUMN_COLUMNS);
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws SQLException {
        return empty(FUNCTION_COLUMNS);
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern) throws SQLException {
        return empty(FUNCTION_COLUMN_COLUMNS);
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) throws SQLException {
        return empty(COLUMN_PRIVILEGE_COLUMNS);
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        return empty(TABLE_PRIVILEGE_COLUMNS);
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable) throws SQLException {
        return empty(ROW_IDENTIFIER_COLUMNS);
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        return empty(ROW_IDENTIFIER_COLUMNS);
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws SQLException {
        return empty(UDT_COLUMNS);
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        return empty(SUPER_TYPE_COLUMNS);
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        return empty(SUPER_TABLE_COLUMNS);
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern) throws SQLException {
        return empty(ATTRIBUTE_COLUMNS);
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return empty(CLIENT_INFO_COLUMNS);
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        return empty(PSEUDO_COLUMN_COLUMNS);
    }

    // Product and driver

    @Override
    public String getDatabaseProductName() {
        return "Qdrant";
    }

    @Override
    public String getDatabaseProductVersion() {
        return connection.serverInfo().version();
    }

    @Override
    public int getDatabaseMajorVersion() {
        return DriverInfo.versionPart(connection.serverInfo().version(), 0);
    }

    @Override
    public int getDatabaseMinorVersion() {
        return DriverInfo.versionPart(connection.serverInfo().version(), 1);
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
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 2;
    }

    @Override
    public String getURL() {
        return connection.url();
    }

    @Override
    public String getUserName() {
        return null;
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    // Names and identifiers: collection names are case-sensitive and may need quoting

    @Override
    public String getIdentifierQuoteString() {
        return "\"";
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public String getSearchStringEscape() {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    @Override
    public String getSchemaTerm() {
        return "schema";
    }

    @Override
    public String getProcedureTerm() {
        return "procedure";
    }

    @Override
    public String getCatalogTerm() {
        return "catalog";
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public String getCatalogSeparator() {
        return ".";
    }

    @Override
    public String getSQLKeywords() {
        return "";
    }

    @Override
    public String getNumericFunctions() {
        return "";
    }

    @Override
    public String getStringFunctions() {
        return "";
    }

    @Override
    public String getSystemFunctions() {
        return "";
    }

    @Override
    public String getTimeDateFunctions() {
        return "";
    }

    // SQL support

    @Override
    public boolean allProceduresAreCallable() {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() {
        return true;
    }

    @Override
    public boolean nullsAreSortedHigh() {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return true;
    }

    @Override
    public boolean usesLocalFiles() {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return false;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return false;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return false;
    }

    @Override
    public boolean supportsGroupBy() {
        return false;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return false;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return false;
    }

    @Override
    public boolean supportsLikeEscapeClause() {
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return false;
    }

    @Override
    public boolean supportsUnion() {
        return false;
    }

    @Override
    public boolean supportsUnionAll() {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() {
        return false;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return false;
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return false;
    }

    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    // Transactions: Qdrant has none

    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsTransactions() {
        return false;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return true;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return true;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    // Result sets: forward-only and read-only

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    // Limits: 0 means no limit or unknown

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 1;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() {
        return 0;
    }

    @Override
    public int getMaxConnections() {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return 0;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    @Override
    public int getMaxStatementLength() {
        return 0;
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 1;
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }

    // Wrapper

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

    // Helpers

    /**
     * There are no catalogs or schemas: accept only filters that don't ask for one.
     */
    private static boolean inScope(String catalog, String schemaPattern) {
        return (catalog == null || catalog.isEmpty()) && (schemaPattern == null || schemaPattern.isEmpty() || schemaPattern.equals("%"));
    }

    private static boolean includesTables(String[] types) {
        if (types == null) {
            return true;
        }

        for (String type : types) {
            if (TABLE_TYPE.equalsIgnoreCase(type)) {
                return true;
            }
        }

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

    private ResultSet empty(List<ResultColumn> columns) throws SQLException {
        connection.checkOpen();
        return ListResultSet.empty(columns);
    }

    /**
     * Payload index types, for {@link #getIndexInfo}; kept in step with the client.
     */
    static Map<String, String> payloadIndexes(TableSchema schema) {
        return schema.collection().payloadIndexes();
    }
}
