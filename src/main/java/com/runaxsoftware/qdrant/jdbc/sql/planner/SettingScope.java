package com.runaxsoftware.qdrant.jdbc.sql.planner;

import java.util.List;
import java.util.Map;

/**
 * The settings one kind of thing has: a collection, a dense vector, or a sparse one.
 *
 * @param groups       its groups of settings
 * @param own          the settings it has outside any group, with what each takes
 * @param quantization whether it has quantization settings, which are JSON
 * @param metadata     whether it has metadata, which is free-form
 */
record SettingScope(List<SettingGroup> groups, Map<String, SettingKind> own,
                    boolean quantization, boolean metadata) {
}
