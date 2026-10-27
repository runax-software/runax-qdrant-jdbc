package com.runaxsoftware.qdrant.jdbc.gateway.models;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VectorFieldTest {

    @Test
    void knowsTheUnnamedVectorByItsEmptyName() {
        assertTrue(new VectorField("", VectorKind.DENSE, 4, "Cosine").isUnnamed());
        assertTrue(new VectorField("", VectorKind.MULTIVECTOR, 2, "Dot").isUnnamed());
        assertFalse(new VectorField("dense", VectorKind.DENSE, 4, "Cosine").isUnnamed());
        assertFalse(new VectorField(" ", VectorKind.DENSE, 4, "Cosine").isUnnamed());
        assertFalse(new VectorField("keywords", VectorKind.SPARSE, 0, null).isUnnamed());
    }
}
