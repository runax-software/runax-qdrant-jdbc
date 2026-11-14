package com.runaxsoftware.qdrant.jdbc.sql.planner;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The settings Qdrant's update-collection API changes, and the server's quotas, as of Qdrant
 * 1.20. Qdrant drops a setting it doesn't know without a word, so the driver checks a name
 * against these first.
 */
final class CollectionSettings {

    /** What a statement may call the quantization settings. */
    static final List<String> QUANTIZATION_NAMES = List.of("quantization", "quantization_config");

    /** The quantization settings' name in the body Qdrant takes. */
    static final String QUANTIZATION_KEY = "quantization_config";

    /** The metadata's name, in a statement and in the body Qdrant takes. */
    static final String METADATA = "metadata";

    /** The word that turns quantization off, and what Qdrant takes for it. */
    static final String QUANTIZATION_OFF = "disabled";
    static final String QUANTIZATION_DISABLED = "Disabled";

    private static final SettingGroup PARAMS = new SettingGroup(List.of("params"), "params",
        Map.of(
            "replication_factor", SettingKind.WHOLE,
            "write_consistency_factor", SettingKind.WHOLE,
            "read_fan_out_factor", SettingKind.WHOLE,
            "read_fan_out_delay_ms", SettingKind.WHOLE,
            "on_disk_payload", SettingKind.BOOL),
        Set.of("payload", "id_tracker"));

    private static final SettingGroup HNSW = new SettingGroup(List.of("hnsw", "hnsw_config"),
        "hnsw_config",
        Map.of(
            "m", SettingKind.WHOLE,
            "ef_construct", SettingKind.WHOLE,
            "full_scan_threshold", SettingKind.WHOLE,
            "max_indexing_threads", SettingKind.WHOLE,
            "payload_m", SettingKind.WHOLE,
            "on_disk", SettingKind.BOOL,
            "inline_storage", SettingKind.BOOL),
        Set.of("memory"));

    // Qdrant takes optimizers_config and answers with optimizer_config, so both name the group
    private static final SettingGroup OPTIMIZERS = new SettingGroup(
        List.of("optimizers", "optimizers_config", "optimizer_config"), "optimizers_config",
        Map.of(
            "deleted_threshold", SettingKind.NUMBER,
            "vacuum_min_vector_number", SettingKind.WHOLE,
            "default_segment_number", SettingKind.WHOLE,
            "max_segment_size", SettingKind.WHOLE,
            "memmap_threshold", SettingKind.WHOLE,
            "indexing_threshold", SettingKind.WHOLE,
            "flush_interval_sec", SettingKind.WHOLE,
            "max_optimization_threads", SettingKind.THREADS,
            "prevent_unoptimized", SettingKind.BOOL),
        Set.of());

    private static final SettingGroup STRICT_MODE = new SettingGroup(
        List.of("strict_mode", "strict_mode_config"), "strict_mode_config",
        Map.ofEntries(
            Map.entry("enabled", SettingKind.BOOL),
            Map.entry("max_query_limit", SettingKind.WHOLE),
            Map.entry("max_timeout", SettingKind.WHOLE),
            Map.entry("unindexed_filtering_retrieve", SettingKind.BOOL),
            Map.entry("unindexed_filtering_update", SettingKind.BOOL),
            Map.entry("search_max_hnsw_ef", SettingKind.WHOLE),
            Map.entry("search_allow_exact", SettingKind.BOOL),
            Map.entry("search_max_oversampling", SettingKind.NUMBER),
            Map.entry("upsert_max_batchsize", SettingKind.WHOLE),
            Map.entry("search_max_batchsize", SettingKind.WHOLE),
            Map.entry("max_collection_vector_size_bytes", SettingKind.WHOLE),
            Map.entry("read_rate_limit", SettingKind.WHOLE),
            Map.entry("write_rate_limit", SettingKind.WHOLE),
            Map.entry("max_collection_payload_size_bytes", SettingKind.WHOLE),
            Map.entry("max_points_count", SettingKind.WHOLE),
            Map.entry("filter_max_conditions", SettingKind.WHOLE),
            Map.entry("condition_max_size", SettingKind.WHOLE),
            Map.entry("max_payload_index_count", SettingKind.WHOLE),
            Map.entry("max_resident_memory_percent", SettingKind.WHOLE)),
        Set.of("multivector_config", "sparse_config"));

    private static final SettingGroup SPARSE_INDEX = new SettingGroup(List.of("index"), "index",
        Map.of(
            "full_scan_threshold", SettingKind.WHOLE,
            "on_disk", SettingKind.BOOL,
            "datatype", SettingKind.WORD),
        Set.of("memory"));

    /** A collection's own settings. */
    static final SettingScope COLLECTION = new SettingScope(
        List.of(PARAMS, HNSW, OPTIMIZERS, STRICT_MODE), Map.of(), true, true);

    /** A dense vector's or a multivector's settings, which override the collection's for it. */
    static final SettingScope DENSE_VECTOR = new SettingScope(
        List.of(HNSW), Map.of("on_disk", SettingKind.BOOL), true, false);

    /** A sparse vector's settings. */
    static final SettingScope SPARSE_VECTOR = new SettingScope(
        List.of(SPARSE_INDEX), Map.of("modifier", SettingKind.WORD), false, false);

    /** The quotas' name, in a statement and in the plan's changes. */
    static final String QUOTAS = "quotas";

    private static final SettingGroup QUOTA_LIMITS = new SettingGroup(List.of(QUOTAS), QUOTAS,
        Map.of(
            "enabled", SettingKind.BOOL,
            "max_resident_memory_percent", SettingKind.PERCENT,
            "max_disk_usage_percent", SettingKind.PERCENT,
            "release_margin_percent", SettingKind.PERCENT),
        Set.of());

    /** The server's own settings, which ALTER SYSTEM changes. */
    static final SettingScope SYSTEM = new SettingScope(
        List.of(QUOTA_LIMITS), Map.of(), false, false);

    private CollectionSettings() {
    }
}
