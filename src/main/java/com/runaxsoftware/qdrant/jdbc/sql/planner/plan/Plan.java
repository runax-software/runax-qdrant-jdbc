package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * What a statement asks Qdrant to do, checked against the collection's schema and ready to run.
 */
public sealed interface Plan permits ConstantPlan, ScrollPlan, QueryPlan, CountPlan, FacetPlan,
    SystemTablePlan, DeletePointsPlan, DropCollectionPlan, CreateCollectionPlan {

    /**
     * Whether running the plan returns rows, as a SELECT does, rather than changing what Qdrant
     * holds and returning a count.
     */
    default boolean returnsRows() {
        return true;
    }
}
