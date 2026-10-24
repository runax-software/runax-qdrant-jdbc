package com.runaxsoftware.qdrant.jdbc.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.JDBCType;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

class ResultColumnsMetaDataTest {

    @ParameterizedTest
    @CsvSource({
        "BOOLEAN,                 false, 5,   java.lang.Boolean,        false",
        "SMALLINT,                true,  6,   java.lang.Short,          false",
        "INTEGER,                 true,  11,  java.lang.Integer,        false",
        "BIGINT,                  true,  20,  java.lang.Long,           false",
        "DOUBLE,                  true,  24,  java.lang.Double,         false",
        "DECIMAL,                 true,  255, java.lang.Object,         false",
        "TIMESTAMP_WITH_TIMEZONE, false, 35,  java.time.OffsetDateTime, false",
        "VARCHAR,                 false, 255, java.lang.String,         true",
        "OTHER,                   false, 255, java.lang.Object,         false",
    })
    void describesEachType(JDBCType type, boolean signed, int displaySize, String className,
                           boolean caseSensitive) throws SQLException {
        ResultSetMetaData metaData = new ResultColumnsMetaData(
            List.of(ResultColumn.of("C", type.getVendorTypeNumber())));

        assertEquals(signed, metaData.isSigned(1));
        assertEquals(displaySize, metaData.getColumnDisplaySize(1));
        assertEquals(className, metaData.getColumnClassName(1));
        assertEquals(caseSensitive, metaData.isCaseSensitive(1));
        assertEquals(type.getVendorTypeNumber(), metaData.getColumnType(1));
        assertEquals(type.getName(), metaData.getColumnTypeName(1));
    }

    @Test
    void usesThePrecisionAsTheDisplaySize() throws SQLException {
        ResultSetMetaData metaData = new ResultColumnsMetaData(List.of(new ResultColumn(
            "vector", Types.VARCHAR, "VECTOR", "products", 1536, ResultSetMetaData.columnNullable)));

        assertEquals(1536, metaData.getColumnDisplaySize(1));
        assertEquals(1536, metaData.getPrecision(1));
        assertEquals("VECTOR", metaData.getColumnTypeName(1));
        assertEquals("products", metaData.getTableName(1));
    }

    @Test
    void describesReadOnlyColumnsWithoutCatalogOrSchema() throws SQLException {
        ResultSetMetaData metaData = new ResultColumnsMetaData(List.of(new ResultColumn(
            "id", Types.VARCHAR, "VARCHAR", "", 0, ResultSetMetaData.columnNoNulls)));

        assertEquals(1, metaData.getColumnCount());
        assertEquals("id", metaData.getColumnLabel(1));
        assertEquals("id", metaData.getColumnName(1));
        assertEquals(ResultSetMetaData.columnNoNulls, metaData.isNullable(1));
        assertFalse(metaData.isAutoIncrement(1));
        assertTrue(metaData.isSearchable(1));
        assertFalse(metaData.isCurrency(1));
        assertTrue(metaData.isReadOnly(1));
        assertFalse(metaData.isWritable(1));
        assertFalse(metaData.isDefinitelyWritable(1));
        assertEquals("", metaData.getSchemaName(1));
        assertEquals("", metaData.getCatalogName(1));
        assertEquals(0, metaData.getScale(1));
    }

    @Test
    void rejectsColumnIndexesOutOfRange() throws Exception {
        ResultSetMetaData metaData = new ResultColumnsMetaData(List.of(ResultColumn.text("A")));
        int checked = 0;

        for (Method method : ResultSetMetaData.class.getMethods()) {
            if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == int.class) {
                for (int column : new int[] {0, 2}) {
                    InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                        () -> method.invoke(metaData, column), method.getName());
                    SQLException error = assertInstanceOf(SQLException.class, failure.getCause());
                    assertEquals("07009", error.getSQLState(), method.getName());
                }

                checked++;
            }
        }

        assertEquals(20, checked);
    }

    @Test
    void keepsItsOwnCopyOfTheColumns() throws SQLException {
        List<ResultColumn> columns = new ArrayList<>(List.of(ResultColumn.text("A")));
        ResultSetMetaData metaData = new ResultColumnsMetaData(columns);

        columns.add(ResultColumn.text("B"));

        assertEquals(1, metaData.getColumnCount());
    }

    @Test
    void unwrapsToItself() throws SQLException {
        ResultSetMetaData metaData = new ResultColumnsMetaData(List.of());

        assertTrue(metaData.isWrapperFor(ResultSetMetaData.class));
        assertFalse(metaData.isWrapperFor(String.class));
        assertSame(metaData, metaData.unwrap(ResultSetMetaData.class));
        assertThrows(SQLException.class, () -> metaData.unwrap(String.class));
    }

    @Test
    void namesStandardTypesAfterJdbc() {
        assertEquals(new ResultColumn("A", Types.VARCHAR, "VARCHAR", "", 0,
            ResultSetMetaData.columnNullable), ResultColumn.text("A"));
        assertEquals("INTEGER", ResultColumn.integer("A").typeName());
        assertEquals("SMALLINT", ResultColumn.smallint("A").typeName());
        assertEquals("BIGINT", ResultColumn.bigint("A").typeName());
        assertEquals("BOOLEAN", ResultColumn.bool("A").typeName());
    }
}
