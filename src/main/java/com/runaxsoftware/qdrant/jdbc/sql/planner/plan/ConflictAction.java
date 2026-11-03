package com.runaxsoftware.qdrant.jdbc.sql.planner.plan;

/**
 * What an INSERT does with a row whose point ID the collection already has, which decides both
 * the mode Qdrant's upsert runs in and whether the driver looks the IDs up first.
 */
public enum ConflictAction {

    /**
     * Fail, as inserting a duplicate primary key does. The IDs are looked up before anything is
     * written, so the statement fails with {@code 23505} having changed nothing, and the
     * {@code insert_only} upsert leaves alone any point that arrives in between.
     */
    FAIL(false),

    /**
     * Leave the point as it is, which {@code insert_only} does by itself: Qdrant skips a point
     * that's already there and reports no error, so no lookup is needed.
     */
    NOTHING(false),

    /**
     * Replace it with the row, which is Qdrant's own {@code upsert} mode.
     */
    UPDATE(true);

    private final boolean replaces;

    ConflictAction(boolean replaces) {
        this.replaces = replaces;
    }

    /**
     * Whether an existing point is replaced, which is Qdrant's {@code upsert} mode rather than
     * its {@code insert_only} one.
     */
    public boolean replaces() {
        return replaces;
    }

    /**
     * Whether the driver has to look the statement's IDs up before it writes, which only a
     * statement that must fail on a taken one needs.
     */
    public boolean checksIds() {
        return this == FAIL;
    }
}
