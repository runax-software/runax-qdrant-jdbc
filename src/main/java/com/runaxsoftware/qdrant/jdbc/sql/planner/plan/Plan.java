package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * What a statement asks Qdrant to do, checked against the collection's schema and ready to run.
 */
public sealed interface Plan permits ConstantPlan, ScrollPlan {}
