package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnDefinition;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnKind;
import com.runaxsoftware.qdrant.jdbc.schema.ColumnType;
import com.runaxsoftware.qdrant.jdbc.schema.TableSchema;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.Plan;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.UpdateCollectionPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;

class SettingsPlannerTest {

    private static final TableSchema DOCS = new TableSchema(
        new QdrantCollection("docs", 3, List.of(), Map.of()),
        List.of(
            new ColumnDefinition("_id", ColumnKind.ID, ColumnType.TEXT, 0, null, ""),
            new ColumnDefinition("lang", ColumnKind.PAYLOAD_FIELD, ColumnType.TEXT, 0, "lang", ""),
            new ColumnDefinition("_vector", ColumnKind.VECTOR, ColumnType.VECTOR, 4, "", ""),
            new ColumnDefinition("dense", ColumnKind.VECTOR, ColumnType.VECTOR, 4, "dense", ""),
            new ColumnDefinition("colbert", ColumnKind.VECTOR, ColumnType.MULTIVECTOR, 4,
                "colbert", ""),
            new ColumnDefinition("keywords", ColumnKind.VECTOR, ColumnType.SPARSE_VECTOR, 0,
                "keywords", "")));

    private static final Catalog CATALOG = name -> {
        if (name.equals("docs")) return DOCS;

        throw new SQLException("Collection " + name + " doesn't exist", "42S02");
    };

    // A collection's own settings are named by the statement, so its columns aren't read
    private static final Catalog NO_LOOKUPS = name -> {
        throw new AssertionError("Looked up " + name);
    };

    private final Planner planner = new Planner();

    // The collection's settings

    @ParameterizedTest
    @ValueSource(strings = {
        "ALTER TABLE docs SET (hnsw.m = 16)",
        "ALTER TABLE docs SET (hnsw_config.m = 16)",
        "ALTER TABLE docs SET (HNSW.M = 16)",
        "ALTER TABLE docs SET (m = 16)",
        "ALTER TABLE docs SET (hnsw = '{\"m\": 16}')",
        "ALTER TABLE collections.docs SET (hnsw_config = '{\"m\": 16}')"})
    void readsOneSettingHoweverItIsSpelled(String sql) throws SQLException {
        UpdateCollectionPlan plan = (UpdateCollectionPlan) plan(sql, NO_LOOKUPS);

        assertEquals("docs", plan.collection());
        assertEquals("{\"hnsw_config\":{\"m\":16}}", plan.changes().toString());
        assertFalse(plan.returnsRows());
        assertTrue(plan.changesData(), "a read-only connection refuses it");
    }

    @Test
    void putsEverySettingInItsGroup() throws SQLException {
        assertEquals("{\"params\":{\"replication_factor\":2,\"on_disk_payload\":false},"
                + "\"hnsw_config\":{\"ef_construct\":200,\"on_disk\":true},"
                + "\"optimizers_config\":{\"indexing_threshold\":20000,\"deleted_threshold\":0.3,"
                + "\"max_optimization_threads\":\"auto\"},"
                + "\"strict_mode_config\":{\"enabled\":true,\"max_query_limit\":500}}",
            changes("ALTER TABLE docs SET (replication_factor = 2, params.on_disk_payload = false, "
                + "hnsw.ef_construct = 200, on_disk = 'true', optimizers.indexing_threshold = 20000, "
                + "optimizer_config.deleted_threshold = 0.3, max_optimization_threads = auto, "
                + "strict_mode.enabled = true, max_query_limit = 500)"));
    }

    @Test
    void takesAThreadCountAsANumberToo() throws SQLException {
        assertEquals("{\"optimizers_config\":{\"max_optimization_threads\":4}}",
            changes("ALTER TABLE docs SET (optimizers.max_optimization_threads = 4)"));
    }

    @Test
    void letsAGroupsJsonReachTheSettingsThatAreObjects() throws SQLException {
        assertEquals("{\"hnsw_config\":{\"m\":8,\"memory\":\"cold\"}}",
            changes("ALTER TABLE docs SET (hnsw = '{\"m\": 8, \"memory\": \"cold\"}')"));
        assertEquals("Setting 'hnsw.memory' is an object, so it goes in with its group as JSON: "
                + "hnsw = '{\"memory\": ...}'",
            error("ALTER TABLE docs SET (hnsw.memory = cold)").getMessage());
    }

    @Test
    void setsQuantizationWholeOrTurnsItOff() throws SQLException {
        assertEquals("{\"quantization_config\":{\"scalar\":{\"type\":\"int8\"}}}",
            changes("ALTER TABLE docs SET (quantization = '{\"scalar\": {\"type\": \"int8\"}}')"));
        assertEquals("{\"quantization_config\":\"Disabled\"}",
            changes("ALTER TABLE docs SET (quantization_config = disabled)"));
        assertTrue(error("ALTER TABLE docs SET (quantization.scalar = 1)").getMessage()
            .contains("quantization is given whole"));
    }

    @Test
    void setsMetadataWholeOrOneKeyAtATime() throws SQLException {
        assertEquals("{\"metadata\":{\"owner\":\"search\",\"tier\":2}}",
            changes("ALTER TABLE docs SET (metadata = '{\"owner\": \"search\", \"tier\": 2}')"));

        // The key keeps its dots and its case: it's the collection's, not the driver's
        assertEquals("{\"metadata\":{\"runax.embed.Dense\":\"openai:text-embedding-3-small\","
                + "\"tier\":2,\"old\":null}}",
            changes("ALTER TABLE docs SET (metadata.runax.embed.Dense = "
                + "'openai:text-embedding-3-small', metadata.tier = 2, metadata.old = NULL)"));
    }

    // What it refuses

    @Test
    void namesTheGroupsForASettingItDoesntKnow() {
        assertEquals("A collection has no setting 'shard_number': its settings are in hnsw, "
                + "metadata, optimizers, params, quantization, strict_mode, written as group.name, "
                + "such as hnsw.ef_construct",
            error("ALTER TABLE docs SET (shard_number = 4)").getMessage());
        assertEquals("A collection has no settings called 'wal', as in 'wal.wal_capacity_mb': it "
                + "has hnsw, metadata, optimizers, params, quantization, strict_mode",
            error("ALTER TABLE docs SET (wal.wal_capacity_mb = 64)").getMessage());
        assertEquals("'ef', as in 'hnsw.ef', isn't a hnsw setting: they are ef_construct, "
                + "full_scan_threshold, inline_storage, m, max_indexing_threads, on_disk, payload_m",
            error("ALTER TABLE docs SET (hnsw.ef = 64)").getMessage());
        assertEquals("'ef', as in 'hnsw', isn't a hnsw setting: they are ef_construct, "
                + "full_scan_threshold, inline_storage, m, max_indexing_threads, on_disk, payload_m",
            error("ALTER TABLE docs SET (hnsw = '{\"ef\": 64}')").getMessage());
    }

    @Test
    void checksAValueAgainstWhatTheSettingTakes() {
        assertEquals("Setting 'hnsw.m' takes a whole number of 0 or more, not 'many'",
            error("ALTER TABLE docs SET (hnsw.m = many)").getMessage());
        assertEquals("22023", error("ALTER TABLE docs SET (hnsw.m = -1)").getSQLState());
        assertEquals("Setting 'on_disk_payload' takes true or false, not 1",
            error("ALTER TABLE docs SET (on_disk_payload = 1)").getMessage());
        assertEquals("Setting 'deleted_threshold' takes a number, not 'low'",
            error("ALTER TABLE docs SET (deleted_threshold = low)").getMessage());
        assertEquals("Setting 'hnsw' takes a JSON object, as '{\"name\": value}', not '[1]'",
            error("ALTER TABLE docs SET (hnsw = '[1]')").getMessage());
        assertEquals("Setting 'metadata' takes a JSON object, as '{\"name\": value}', not 'x'",
            error("ALTER TABLE docs SET (metadata = x)").getMessage());
    }

    @Test
    void refusesOneSettingGivenTwoWays() {
        assertEquals("Setting 'm' gives 'm' a second time in one statement",
            error("ALTER TABLE docs SET (hnsw.m = 8, m = 16)").getMessage());
        assertEquals("Setting 'hnsw' gives 'm' a second time in one statement",
            error("ALTER TABLE docs SET (hnsw.m = 8, hnsw = '{\"m\": 16}')").getMessage());
    }

    @Test
    void keepsTheSystemTablesAsTheyAre() {
        assertThrows(SQLFeatureNotSupportedException.class,
            () -> plan("ALTER TABLE system.collections SET (hnsw.m = 8)", NO_LOOKUPS));
    }

    // A vector's settings

    @Test
    void changesADenseVectorsSettingsUnderItsName() throws SQLException {
        assertEquals("{\"vectors\":{\"dense\":{\"on_disk\":true,\"hnsw_config\":{\"m\":8,"
                + "\"on_disk\":false},\"quantization_config\":\"Disabled\"}}}",
            changes("ALTER TABLE docs ALTER COLUMN dense SET (on_disk = true, hnsw.m = 8, "
                + "hnsw.on_disk = false, quantization = disabled)"));
        assertEquals("{\"vectors\":{\"colbert\":{\"hnsw_config\":{\"ef_construct\":64}}}}",
            changes("ALTER TABLE docs ALTER colbert SET (ef_construct = 64)"));
    }

    @Test
    void changesTheUnnamedVectorUnderTheEmptyName() throws SQLException {
        assertEquals("{\"vectors\":{\"\":{\"on_disk\":true}}}",
            changes("ALTER TABLE docs ALTER COLUMN _vector SET (on_disk = true)"));
    }

    @Test
    void changesASparseVectorsSettings() throws SQLException {
        assertEquals("{\"sparse_vectors\":{\"keywords\":{\"modifier\":\"idf\","
                + "\"index\":{\"on_disk\":true,\"full_scan_threshold\":5000}}}}",
            changes("ALTER TABLE docs ALTER COLUMN keywords SET (modifier = IDF, "
                + "index.on_disk = true, full_scan_threshold = 5000)"));
    }

    @Test
    void givesAVectorOnlyTheSettingsAVectorHas() {
        assertEquals("A vector has no setting 'replication_factor': its settings are in hnsw, "
                + "on_disk, quantization, written as group.name, such as hnsw.ef_construct",
            error("ALTER TABLE docs ALTER COLUMN dense SET (replication_factor = 2)").getMessage());
        assertEquals("A sparse vector has no settings called 'hnsw', as in 'hnsw.m': it has "
                + "index, modifier",
            error("ALTER TABLE docs ALTER COLUMN keywords SET (hnsw.m = 8)").getMessage());
        assertTrue(error("ALTER TABLE docs ALTER COLUMN dense SET (metadata.a = 1)").getMessage()
            .startsWith("A vector has no settings called 'metadata'"));
    }

    @Test
    void altersOnlyAColumnThatIsAVector() {
        SQLException error = error("ALTER TABLE docs ALTER COLUMN lang SET (on_disk = true)");

        assertEquals("42S22", error.getSQLState());
        assertEquals("docs has no vector 'lang': ALTER COLUMN changes a vector's settings, and a "
            + "payload field has none", error.getMessage());
        assertEquals("42S22",
            error("ALTER TABLE docs ALTER COLUMN nope SET (on_disk = true)").getSQLState());
    }

    private String changes(String sql) throws SQLException {
        return ((UpdateCollectionPlan) plan(sql, CATALOG)).changes().toString();
    }

    private SQLException error(String sql) {
        return assertThrows(SQLException.class, () -> plan(sql, CATALOG));
    }

    private Plan plan(String sql, Catalog catalog) throws SQLException {
        return planner.plan(Parser.parse(sql), catalog);
    }
}
