package com.runaxsoftware.qdrant.jdbc.schema;

import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorField;
import com.runaxsoftware.qdrant.jdbc.gateway.models.VectorKind;

import java.util.Comparator;
import java.util.List;

final class VectorColumns {
    /**
     * Dense and multivectors first, then sparse vectors; each group by name.
     */
    private static final Comparator<VectorField> ORDER =
        Comparator.comparing((VectorField vector) -> vector.kind() == VectorKind.SPARSE)
            .thenComparing(VectorField::name);

    private VectorColumns() {
    }

    /**
     * One column per vector, in {@link #ORDER}.
     */
    static List<ColumnDefinition> columns(List<VectorField> vectors) {
        return vectors.stream().sorted(ORDER).map(vector -> new ColumnDefinition(columnName(vector),
                ColumnKind.VECTOR, ColumnType.forVector(vector.kind()), vector.size(), vector.name(), remarks(vector)))
            .toList();
    }

    /**
     * The vector's name; {@code _vector} for the unnamed one; with {@code _vector} appended when
     * the name is reserved, so a vector called {@code _score} becomes {@code _score_vector}.
     */
    private static String columnName(VectorField vector) {
        if (vector.isUnnamed()) return DriverColumns.VECTOR.columnName();
        if (DriverColumns.isReserved(vector.name())) return vector.name() + "_vector";

        return vector.name();
    }

    private static String remarks(VectorField vector) {
        return switch (vector.kind()) {
            case DENSE -> "Dense vector, " + vector.size() + " dimensions, " + vector.distance() + " distance";
            case MULTIVECTOR ->
                "Multivector of " + vector.size() + "-dimension vectors, " + vector.distance() + " distance";
            case SPARSE -> "Sparse vector";
        };
    }
}
