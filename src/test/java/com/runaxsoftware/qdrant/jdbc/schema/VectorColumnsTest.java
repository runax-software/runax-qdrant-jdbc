package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;

import org.junit.jupiter.api.Test;

import java.util.List;

class VectorColumnsTest {

    @Test
    void namesTheUnnamedVectorUnderscoreVector() {
        ColumnDefinition column = VectorColumns.columns(List.of(
            new VectorField("", VectorKind.DENSE, 4, "Cosine"))).get(0);

        assertEquals(new ColumnDefinition("_vector", ColumnKind.VECTOR, ColumnType.VECTOR, 4, "",
            "Dense vector, 4 dimensions, Cosine distance"), column);
    }

    @Test
    void keepsTheNamesOfNamedVectors() {
        ColumnDefinition column = VectorColumns.columns(List.of(
            new VectorField("text", VectorKind.DENSE, 384, "Dot"))).get(0);

        assertEquals("text", column.name());
        assertEquals("text", column.source());
        assertEquals(384, column.size());
    }

    @Test
    void renamesVectorsNamedLikeTheDriversColumns() {
        List<ColumnDefinition> columns = VectorColumns.columns(List.of(
            new VectorField("_score", VectorKind.DENSE, 3, "Dot"),
            new VectorField("_id", VectorKind.DENSE, 3, "Dot"),
            new VectorField("_vector", VectorKind.DENSE, 3, "Dot"),
            new VectorField("_other", VectorKind.DENSE, 3, "Dot")));

        assertEquals(List.of("_id_vector", "_other", "_score_vector", "_vector_vector"),
            columns.stream().map(ColumnDefinition::name).toList());
        assertEquals(List.of("_id", "_other", "_score", "_vector"),
            columns.stream().map(ColumnDefinition::source).toList());
    }

    @Test
    void putsDenseAndMultivectorsBeforeSparseOnesEachByName() {
        List<ColumnDefinition> columns = VectorColumns.columns(List.of(
            new VectorField("splade", VectorKind.SPARSE, 0, null),
            new VectorField("text", VectorKind.DENSE, 4, "Dot"),
            new VectorField("bm25", VectorKind.SPARSE, 0, null),
            new VectorField("colbert", VectorKind.MULTIVECTOR, 2, "Cosine"),
            new VectorField("image", VectorKind.DENSE, 8, "Euclid")));

        assertEquals(List.of("colbert", "image", "text", "bm25", "splade"),
            columns.stream().map(ColumnDefinition::name).toList());
    }

    @Test
    void typesAndDescribesEachKindOfVector() {
        List<ColumnDefinition> columns = VectorColumns.columns(List.of(
            new VectorField("a", VectorKind.DENSE, 4, "Dot"),
            new VectorField("b", VectorKind.MULTIVECTOR, 2, "Cosine"),
            new VectorField("c", VectorKind.SPARSE, 0, null)));

        assertEquals(ColumnType.VECTOR, columns.get(0).type());
        assertEquals("Dense vector, 4 dimensions, Dot distance", columns.get(0).remarks());
        assertEquals(ColumnType.MULTIVECTOR, columns.get(1).type());
        assertEquals("Multivector of 2-dimension vectors, Cosine distance",
            columns.get(1).remarks());
        assertEquals(ColumnType.SPARSE_VECTOR, columns.get(2).type());
        assertEquals("Sparse vector", columns.get(2).remarks());
        assertEquals(0, columns.get(2).size());
    }

    @Test
    void makesVectorColumnsNullable() {
        for (ColumnDefinition column : VectorColumns.columns(List.of(
            new VectorField("", VectorKind.DENSE, 4, "Cosine"),
            new VectorField("keywords", VectorKind.SPARSE, 0, null)))) {
            assertEquals(ColumnKind.VECTOR, column.kind());
            assertTrue(column.nullable());
        }
    }

    @Test
    void makesNoColumnsWithoutVectors() {
        assertTrue(VectorColumns.columns(List.of()).isEmpty());
    }

    @Test
    void returnsColumnsThatCannotChange() {
        List<ColumnDefinition> columns = VectorColumns.columns(List.of(
            new VectorField("", VectorKind.DENSE, 4, "Cosine")));

        assertThrows(UnsupportedOperationException.class, columns::clear);
    }
}
