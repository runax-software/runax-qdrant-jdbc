package com.runaxsoftware.qdrant.jdbc.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.resultset.ResultColumn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

class MetadataColumnsTest {

    @Test
    void coversEveryDatabaseMetaDataResult() {
        assertEquals(List.of("TABLES", "SCHEMAS", "CATALOGS", "TABLE_TYPES", "COLUMNS",
                "PRIMARY_KEYS", "INDEX_INFO", "FOREIGN_KEYS", "TYPE_INFO", "PROCEDURES",
                "PROCEDURE_COLUMNS", "FUNCTIONS", "FUNCTION_COLUMNS", "COLUMN_PRIVILEGES",
                "TABLE_PRIVILEGES", "ROW_IDENTIFIER", "UDTS", "SUPER_TYPES", "SUPER_TABLES",
                "ATTRIBUTES", "CLIENT_INFO_PROPERTIES", "PSEUDO_COLUMNS"),
            Arrays.stream(MetadataColumns.values()).map(Enum::name).toList());
    }

    @ParameterizedTest
    @CsvSource({
        "TABLES, 10, TABLE_CAT, REF_GENERATION",
        "SCHEMAS, 2, TABLE_SCHEM, TABLE_CATALOG",
        "CATALOGS, 1, TABLE_CAT, TABLE_CAT",
        "TABLE_TYPES, 1, TABLE_TYPE, TABLE_TYPE",
        "COLUMNS, 24, TABLE_CAT, IS_GENERATEDCOLUMN",
        "PRIMARY_KEYS, 6, TABLE_CAT, PK_NAME",
        "INDEX_INFO, 13, TABLE_CAT, FILTER_CONDITION",
        "FOREIGN_KEYS, 14, PKTABLE_CAT, DEFERRABILITY",
        "TYPE_INFO, 18, TYPE_NAME, NUM_PREC_RADIX",
        "PROCEDURES, 9, PROCEDURE_CAT, SPECIFIC_NAME",
        "PROCEDURE_COLUMNS, 20, PROCEDURE_CAT, SPECIFIC_NAME",
        "FUNCTIONS, 6, FUNCTION_CAT, SPECIFIC_NAME",
        "FUNCTION_COLUMNS, 17, FUNCTION_CAT, SPECIFIC_NAME",
        "COLUMN_PRIVILEGES, 8, TABLE_CAT, IS_GRANTABLE",
        "TABLE_PRIVILEGES, 7, TABLE_CAT, IS_GRANTABLE",
        "ROW_IDENTIFIER, 8, SCOPE, PSEUDO_COLUMN",
        "UDTS, 7, TYPE_CAT, BASE_TYPE",
        "SUPER_TYPES, 6, TYPE_CAT, SUPERTYPE_NAME",
        "SUPER_TABLES, 4, TABLE_CAT, SUPERTABLE_NAME",
        "ATTRIBUTES, 21, TYPE_CAT, SOURCE_DATA_TYPE",
        "CLIENT_INFO_PROPERTIES, 4, NAME, DESCRIPTION",
        "PSEUDO_COLUMNS, 12, TABLE_CAT, IS_NULLABLE"})
    void hasTheColumnsJdbcDefines(MetadataColumns result, int count, String first, String last) {
        List<String> names = names(result);

        assertEquals(count, names.size());
        assertEquals(first, names.get(0));
        assertEquals(last, names.get(names.size() - 1));
    }

    @ParameterizedTest
    @EnumSource(MetadataColumns.class)
    void namesEachColumnOnceAndAllowsNulls(MetadataColumns result) {
        List<String> names = names(result);

        assertEquals(names.size(), new HashSet<>(names).size(), "duplicate column in " + result);

        for (ResultColumn column : result.columns()) {
            assertEquals(ResultSetMetaData.columnNullable, column.nullable(), column.name());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "TABLES, TABLE_NAME, " + Types.VARCHAR,
        "COLUMNS, DATA_TYPE, " + Types.INTEGER,
        "COLUMNS, SOURCE_DATA_TYPE, " + Types.SMALLINT,
        "PRIMARY_KEYS, KEY_SEQ, " + Types.SMALLINT,
        "INDEX_INFO, NON_UNIQUE, " + Types.BOOLEAN,
        "INDEX_INFO, TYPE, " + Types.SMALLINT,
        "INDEX_INFO, CARDINALITY, " + Types.BIGINT,
        "TYPE_INFO, CASE_SENSITIVE, " + Types.BOOLEAN,
        "TYPE_INFO, SEARCHABLE, " + Types.SMALLINT,
        "TYPE_INFO, PRECISION, " + Types.INTEGER,
        "FOREIGN_KEYS, DEFERRABILITY, " + Types.SMALLINT,
        "ROW_IDENTIFIER, SCOPE, " + Types.SMALLINT,
        "CLIENT_INFO_PROPERTIES, MAX_LEN, " + Types.INTEGER})
    void typesColumnsTheWayJdbcDoes(MetadataColumns result, String name, int sqlType) {
        ResultColumn column = result.columns().stream()
            .filter(candidate -> candidate.name().equals(name))
            .findFirst()
            .orElseThrow();

        assertEquals(sqlType, column.sqlType());
    }

    @Test
    void keepsItsColumnsFixed() {
        List<ResultColumn> columns = MetadataColumns.TABLES.columns();

        assertThrows(UnsupportedOperationException.class,
            () -> columns.add(ResultColumn.of("EXTRA", Types.VARCHAR)));
    }

    @Test
    void returnsTheRowsInOrderWithItsColumns() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {"TABLE"});
        rows.add(new Object[] {"VIEW"});

        try (ResultSet result = MetadataColumns.TABLE_TYPES.rows(rows)) {
            assertEquals("TABLE_TYPE", result.getMetaData().getColumnLabel(1));
            assertEquals(1, result.getMetaData().getColumnCount());
            assertNull(result.getStatement(), "metadata results belong to no statement");

            assertTrue(result.next());
            assertEquals("TABLE", result.getString("TABLE_TYPE"));
            assertTrue(result.next());
            assertEquals("VIEW", result.getString(1));
            assertFalse(result.next());
        }
    }

    @Test
    void readsTypedValuesFromRows() throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {null, null, "products", "_id", (short) 1, "products_pkey"});

        try (ResultSet result = MetadataColumns.PRIMARY_KEYS.rows(rows)) {
            assertTrue(result.next());
            assertNull(result.getString("TABLE_CAT"));
            assertEquals(1, result.getShort("KEY_SEQ"));
            assertEquals("products_pkey", result.getString("PK_NAME"));
        }
    }

    @Test
    void rejectsRowsWithTheWrongNumberOfValues() {
        List<Object[]> tooMany = new ArrayList<>();
        tooMany.add(new Object[] {"TABLE", "extra"});
        List<Object[]> tooFew = new ArrayList<>();
        tooFew.add(new Object[] {"products"});

        assertThrows(IllegalArgumentException.class, () -> MetadataColumns.TABLE_TYPES.rows(tooMany));
        assertThrows(IllegalArgumentException.class, () -> MetadataColumns.TABLES.rows(tooFew));
    }

    @ParameterizedTest
    @EnumSource(MetadataColumns.class)
    void returnsEmptyResultsWithItsColumns(MetadataColumns result) throws SQLException {
        try (ResultSet empty = result.empty()) {
            ResultSetMetaData metaData = empty.getMetaData();
            List<String> labels = new ArrayList<>();

            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                labels.add(metaData.getColumnLabel(i));
            }

            assertEquals(names(result), labels);
            assertFalse(empty.next());
        }
    }

    @Test
    void returnsANewResultEachTime() throws SQLException {
        ResultSet first = MetadataColumns.CATALOGS.empty();
        ResultSet second = MetadataColumns.CATALOGS.empty();

        assertNotSame(first, second);
        first.close();
        assertFalse(second.isClosed());
        second.close();
    }

    private static List<String> names(MetadataColumns result) {
        return result.columns().stream().map(ResultColumn::name).toList();
    }
}
