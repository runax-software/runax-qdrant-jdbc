package com.runaxsoftware.qdrant.jdbc.sql.planner;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One group of settings Qdrant changes together, such as a collection's HNSW settings: an
 * object of the update-collection body.
 *
 * @param names   what a statement may call the group, the short name first and Qdrant's after
 * @param key     the group's name in the body Qdrant takes
 * @param fields  the settings a statement gives one by one, with what each takes
 * @param objects the settings that are objects themselves, which only the group's JSON form
 *                reaches
 */
record SettingGroup(List<String> names, String key, Map<String, SettingKind> fields,
                    Set<String> objects) {

    /**
     * The name the driver's messages use for the group.
     */
    String name() {
        return names.get(0);
    }

    boolean named(String word) {
        return names.contains(word);
    }
}
