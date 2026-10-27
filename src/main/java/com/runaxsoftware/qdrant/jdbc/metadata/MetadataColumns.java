package com.runaxsoftware.qdrant.jdbc.metadata;

import static com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn.bigint;
import static com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn.bool;
import static com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn.integer;
import static com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn.smallint;
import static com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn.text;

import com.runaxsoftware.qdrant.jdbc.resultset.ListResultSet;
import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;

import java.sql.ResultSet;
import java.util.List;

/**
 * The column layout of each DatabaseMetaData result, with the exact names JDBC requires.
 */
enum MetadataColumns {
    /**
     * {@code getTables}
     */
    TABLES(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("TABLE_TYPE"),
        text("REMARKS"), text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"),
        text("SELF_REFERENCING_COL_NAME"), text("REF_GENERATION")),

    /**
     * {@code getSchemas}
     */
    SCHEMAS(text("TABLE_SCHEM"), text("TABLE_CATALOG")),

    /**
     * {@code getCatalogs}
     */
    CATALOGS(text("TABLE_CAT")),

    /**
     * {@code getTableTypes}
     */
    TABLE_TYPES(text("TABLE_TYPE")),

    /**
     * {@code getColumns}
     */
    COLUMNS(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"),
        integer("DATA_TYPE"), text("TYPE_NAME"), integer("COLUMN_SIZE"), integer("BUFFER_LENGTH"),
        integer("DECIMAL_DIGITS"), integer("NUM_PREC_RADIX"), integer("NULLABLE"), text("REMARKS"),
        text("COLUMN_DEF"), integer("SQL_DATA_TYPE"), integer("SQL_DATETIME_SUB"),
        integer("CHAR_OCTET_LENGTH"), integer("ORDINAL_POSITION"), text("IS_NULLABLE"),
        text("SCOPE_CATALOG"), text("SCOPE_SCHEMA"), text("SCOPE_TABLE"),
        smallint("SOURCE_DATA_TYPE"), text("IS_AUTOINCREMENT"), text("IS_GENERATEDCOLUMN")),

    /**
     * {@code getPrimaryKeys}
     */
    PRIMARY_KEYS(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"),
        smallint("KEY_SEQ"), text("PK_NAME")),

    /**
     * {@code getIndexInfo}
     */
    INDEX_INFO(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), bool("NON_UNIQUE"),
        text("INDEX_QUALIFIER"), text("INDEX_NAME"), smallint("TYPE"), smallint("ORDINAL_POSITION"),
        text("COLUMN_NAME"), text("ASC_OR_DESC"), bigint("CARDINALITY"), bigint("PAGES"),
        text("FILTER_CONDITION")),

    /**
     * {@code getImportedKeys}, {@code getExportedKeys}, {@code getCrossReference}
     */
    FOREIGN_KEYS(text("PKTABLE_CAT"), text("PKTABLE_SCHEM"), text("PKTABLE_NAME"),
        text("PKCOLUMN_NAME"), text("FKTABLE_CAT"), text("FKTABLE_SCHEM"), text("FKTABLE_NAME"),
        text("FKCOLUMN_NAME"), smallint("KEY_SEQ"), smallint("UPDATE_RULE"),
        smallint("DELETE_RULE"), text("FK_NAME"), text("PK_NAME"), smallint("DEFERRABILITY")),

    /**
     * {@code getTypeInfo}
     */
    TYPE_INFO(text("TYPE_NAME"), integer("DATA_TYPE"), integer("PRECISION"),
        text("LITERAL_PREFIX"), text("LITERAL_SUFFIX"), text("CREATE_PARAMS"), smallint("NULLABLE"),
        bool("CASE_SENSITIVE"), smallint("SEARCHABLE"), bool("UNSIGNED_ATTRIBUTE"),
        bool("FIXED_PREC_SCALE"), bool("AUTO_INCREMENT"), text("LOCAL_TYPE_NAME"),
        smallint("MINIMUM_SCALE"), smallint("MAXIMUM_SCALE"), integer("SQL_DATA_TYPE"),
        integer("SQL_DATETIME_SUB"), integer("NUM_PREC_RADIX")),

    /**
     * {@code getProcedures}
     */
    PROCEDURES(text("PROCEDURE_CAT"), text("PROCEDURE_SCHEM"), text("PROCEDURE_NAME"),
        text("RESERVED1"), text("RESERVED2"), text("RESERVED3"), text("REMARKS"),
        smallint("PROCEDURE_TYPE"), text("SPECIFIC_NAME")),

    /**
     * {@code getProcedureColumns}
     */
    PROCEDURE_COLUMNS(text("PROCEDURE_CAT"), text("PROCEDURE_SCHEM"), text("PROCEDURE_NAME"),
        text("COLUMN_NAME"), smallint("COLUMN_TYPE"), integer("DATA_TYPE"), text("TYPE_NAME"),
        integer("PRECISION"), integer("LENGTH"), smallint("SCALE"), smallint("RADIX"),
        smallint("NULLABLE"), text("REMARKS"), text("COLUMN_DEF"), integer("SQL_DATA_TYPE"),
        integer("SQL_DATETIME_SUB"), integer("CHAR_OCTET_LENGTH"), integer("ORDINAL_POSITION"),
        text("IS_NULLABLE"), text("SPECIFIC_NAME")),

    /**
     * {@code getFunctions}
     */
    FUNCTIONS(text("FUNCTION_CAT"), text("FUNCTION_SCHEM"), text("FUNCTION_NAME"),
        text("REMARKS"), smallint("FUNCTION_TYPE"), text("SPECIFIC_NAME")),

    /**
     * {@code getFunctionColumns}
     */
    FUNCTION_COLUMNS(text("FUNCTION_CAT"), text("FUNCTION_SCHEM"), text("FUNCTION_NAME"),
        text("COLUMN_NAME"), smallint("COLUMN_TYPE"), integer("DATA_TYPE"), text("TYPE_NAME"),
        integer("PRECISION"), integer("LENGTH"), smallint("SCALE"), smallint("RADIX"),
        smallint("NULLABLE"), text("REMARKS"), integer("CHAR_OCTET_LENGTH"),
        integer("ORDINAL_POSITION"), text("IS_NULLABLE"), text("SPECIFIC_NAME")),

    /**
     * {@code getColumnPrivileges}
     */
    COLUMN_PRIVILEGES(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"),
        text("COLUMN_NAME"), text("GRANTOR"), text("GRANTEE"), text("PRIVILEGE"),
        text("IS_GRANTABLE")),

    /**
     * {@code getTablePrivileges}
     */
    TABLE_PRIVILEGES(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("GRANTOR"),
        text("GRANTEE"), text("PRIVILEGE"), text("IS_GRANTABLE")),

    /**
     * {@code getBestRowIdentifier}, {@code getVersionColumns}
     */
    ROW_IDENTIFIER(smallint("SCOPE"), text("COLUMN_NAME"), integer("DATA_TYPE"), text("TYPE_NAME"),
        integer("COLUMN_SIZE"), integer("BUFFER_LENGTH"), smallint("DECIMAL_DIGITS"),
        smallint("PSEUDO_COLUMN")),

    /**
     * {@code getUDTs}
     */
    UDTS(text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("CLASS_NAME"),
        integer("DATA_TYPE"), text("REMARKS"), smallint("BASE_TYPE")),

    /**
     * {@code getSuperTypes}
     */
    SUPER_TYPES(text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("SUPERTYPE_CAT"),
        text("SUPERTYPE_SCHEM"), text("SUPERTYPE_NAME")),

    /**
     * {@code getSuperTables}
     */
    SUPER_TABLES(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"),
        text("SUPERTABLE_NAME")),

    /**
     * {@code getAttributes}
     */
    ATTRIBUTES(text("TYPE_CAT"), text("TYPE_SCHEM"), text("TYPE_NAME"), text("ATTR_NAME"),
        integer("DATA_TYPE"), text("ATTR_TYPE_NAME"), integer("ATTR_SIZE"),
        integer("DECIMAL_DIGITS"), integer("NUM_PREC_RADIX"), integer("NULLABLE"), text("REMARKS"),
        text("ATTR_DEF"), integer("SQL_DATA_TYPE"), integer("SQL_DATETIME_SUB"),
        integer("CHAR_OCTET_LENGTH"), integer("ORDINAL_POSITION"), text("IS_NULLABLE"),
        text("SCOPE_CATALOG"), text("SCOPE_SCHEMA"), text("SCOPE_TABLE"),
        smallint("SOURCE_DATA_TYPE")),

    /**
     * {@code getClientInfoProperties}
     */
    CLIENT_INFO_PROPERTIES(text("NAME"), integer("MAX_LEN"), text("DEFAULT_VALUE"),
        text("DESCRIPTION")),

    /**
     * {@code getPseudoColumns}
     */
    PSEUDO_COLUMNS(text("TABLE_CAT"), text("TABLE_SCHEM"), text("TABLE_NAME"), text("COLUMN_NAME"),
        integer("DATA_TYPE"), integer("COLUMN_SIZE"), integer("DECIMAL_DIGITS"),
        integer("NUM_PREC_RADIX"), text("COLUMN_USAGE"), text("REMARKS"),
        integer("CHAR_OCTET_LENGTH"), text("IS_NULLABLE"));

    private final List<ResultColumn> columns;

    MetadataColumns(ResultColumn... columns) {
        this.columns = List.of(columns);
    }

    List<ResultColumn> columns() {
        return columns;
    }


    /**
     * A result of this shape with the given rows.
     */
    ResultSet rows(List<Object[]> rows) {
        return new ListResultSet(columns, rows, null);
    }

    /**
     * A result of this shape with no rows, for what Qdrant doesn't have.
     */
    ResultSet empty() {
        return ListResultSet.empty(columns);
    }
}
