package com.runaxsoftware.qdrant.jdbc.sql.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;
import com.runaxsoftware.qdrant.jdbc.procedure.Procedure;
import com.runaxsoftware.qdrant.jdbc.procedure.Transfer;
import com.runaxsoftware.qdrant.jdbc.sql.parser.Parser;
import com.runaxsoftware.qdrant.jdbc.sql.planner.plan.ProcedurePlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.SQLException;

class CallPlannerTest {

    // A CALL is one request built from what it says, so nothing is looked up to plan it
    private static final Catalog NO_LOOKUPS = name -> {
        throw new AssertionError("Looked up " + name);
    };

    private final Planner planner = new Planner();

    @ParameterizedTest
    @ValueSource(strings = {
        "CALL create_shard_key('docs', 'eu')",
        "CALL qdrant.create_shard_key('docs', 'eu')",
        "CALL system.create_shard_key('docs', 'eu')",
        "CALL QDRANT.SYSTEM.CREATE_SHARD_KEY('docs', 'eu')",
        "CALL create_shard_key(collection => 'docs', shard_key => 'eu')",
        "CALL create_shard_key('docs', SHARD_KEY => 'eu', shards_number => NULL)"})
    void findsAProcedureAndItsArgumentsHoweverTheyAreGiven(String sql) throws SQLException {
        ProcedurePlan plan = plan(sql);

        assertEquals(Procedure.CREATE_SHARD_KEY, plan.procedure());
        assertEquals(HttpMethod.PUT, plan.method());
        assertEquals("/collections/docs/shards", plan.path());
        assertEquals("{\"shard_key\":\"eu\"}", plan.body().toString());
        assertFalse(plan.returnsRows());
        assertTrue(plan.changesData(), "a read-only connection refuses it");
    }

    @Test
    void sendsEveryArgumentOfAShardKey() throws SQLException {
        ProcedurePlan plan = plan("CALL create_shard_key('my docs', 7, shards_number => 2, "
            + "replication_factor => 1, placement => '[11, 12]')");

        assertEquals("/collections/my%20docs/shards", plan.path());
        assertEquals("{\"shard_key\":7,\"shards_number\":2,\"replication_factor\":1,"
            + "\"placement\":[11,12]}", plan.body().toString());

        ProcedurePlan delete = plan("CALL delete_shard_key('docs', 'eu')");
        assertEquals(HttpMethod.POST, delete.method());
        assertEquals("/collections/docs/shards/delete", delete.path());
        assertEquals("{\"shard_key\":\"eu\"}", delete.body().toString());
    }

    @Test
    void holdsAClusterOperationsArgumentsUnderItsName() throws SQLException {
        ProcedurePlan move = plan("CALL move_shard('docs', 0, 11, 12, method => 'snapshot')");
        assertEquals(HttpMethod.POST, move.method());
        assertEquals("/collections/docs/cluster", move.path());
        assertEquals("{\"move_shard\":{\"shard_id\":0,\"from_peer_id\":11,\"to_peer_id\":12,"
            + "\"method\":\"snapshot\"}}", move.body().toString());

        assertEquals("{\"replicate_shard\":{\"shard_id\":0,\"from_peer_id\":11,\"to_peer_id\":12}}",
            plan("CALL replicate_shard('docs', 0, 11, 12)").body().toString());
        assertEquals("{\"abort_transfer\":{\"shard_id\":0,\"from_peer_id\":11,\"to_peer_id\":12}}",
            plan("CALL abort_shard_transfer('docs', 0, 11, 12)").body().toString());
        assertEquals("{\"restart_transfer\":{\"shard_id\":0,\"from_peer_id\":11,\"to_peer_id\":12,"
            + "\"method\":\"wal_delta\"}}",
            plan("CALL restart_shard_transfer('docs', 0, 11, 12, 'wal_delta')").body().toString());
        assertEquals("{\"drop_replica\":{\"shard_id\":3,\"peer_id\":12}}",
            plan("CALL drop_replica('docs', 3, 12)").body().toString());
        assertEquals("{\"start_resharding\":{\"direction\":\"up\",\"shard_key\":\"eu\"}}",
            plan("CALL start_resharding('docs', 'up', shard_key => 'eu')").body().toString());
        assertEquals("{\"abort_resharding\":{}}",
            plan("CALL abort_resharding('docs')").body().toString());
        assertEquals("{\"replicate_points\":{\"from_shard_key\":\"eu\",\"to_shard_key\":\"us\","
            + "\"filter\":{\"must\":[]}}}",
            plan("CALL replicate_points('docs', 'eu', 'us', filter => '{\"must\": []}')")
                .body().toString());
    }

    @Test
    void putsPathAndQueryArgumentsWhereTheyGo() throws SQLException {
        ProcedurePlan remove = plan("CALL remove_peer(8882707261238555, force => true)");
        assertEquals(HttpMethod.DELETE, remove.method());
        assertEquals("/cluster/peer/8882707261238555?force=true", remove.path());
        assertNull(remove.body());

        assertEquals("/cluster/peer/7?force=false&timeout=30",
            plan("CALL remove_peer(7, timeout => 30, force => false)").path());

        ProcedurePlan recover = plan("CALL qdrant.recover_raft()");
        assertEquals(HttpMethod.POST, recover.method());
        assertEquals("/cluster/recover", recover.path());
        assertNull(recover.body());
    }

    @Test
    void namesTheProceduresForOneItDoesntHave() {
        SQLException error = error("CALL qdrant.compact('docs')");

        assertEquals("42883", error.getSQLState());
        assertEquals("Unknown procedure 'qdrant.compact': there are abort_resharding, "
            + "abort_shard_transfer, create_full_snapshot, create_shard_key, "
            + "create_shard_snapshot, create_snapshot, delete_full_snapshot, delete_shard_key, "
            + "delete_shard_snapshot, delete_snapshot, download_full_snapshot, "
            + "download_shard_snapshot, download_snapshot, drop_replica, move_shard, "
            + "recover_raft, recover_shard_snapshot, recover_snapshot, remove_peer, "
            + "replicate_points, replicate_shard, restart_shard_transfer, start_resharding, "
            + "stream_shard_snapshot, upload_shard_snapshot, upload_snapshot",
            error.getMessage());
        assertEquals("42883", error("CALL public.create_shard_key('docs', 'eu')").getSQLState());
    }

    @Test
    void saysWhatAProcedureTakesWhenTheArgumentsDontFit() {
        assertEquals("delete_shard_key needs 'shard_key': delete_shard_key(collection, shard_key)",
            error("CALL delete_shard_key('docs')").getMessage());
        assertEquals("delete_shard_key takes at most 2: delete_shard_key(collection, shard_key)",
            error("CALL delete_shard_key('docs', 'eu', 'us')").getMessage());
        assertEquals("recover_raft takes no arguments", error("CALL recover_raft(1)").getMessage());
        assertEquals("create_shard_key has no argument 'shards': create_shard_key(collection, "
                + "shard_key [, shards_number => ...] [, replication_factor => ...] "
                + "[, placement => ...])",
            error("CALL create_shard_key('docs', 'eu', shards => 2)").getMessage());
        assertEquals("Argument 'shard_key' of delete_shard_key is given twice",
            error("CALL delete_shard_key('docs', 'eu', shard_key => 'us')").getMessage());
        assertTrue(error("CALL delete_shard_key(collection => 'docs', 'eu')").getMessage()
            .startsWith("An argument given by position can't follow one given by name"));
        assertEquals("delete_shard_key needs 'shard_key': delete_shard_key(collection, shard_key)",
            error("CALL delete_shard_key('docs', NULL)").getMessage());
    }

    @Test
    void checksAnArgumentAgainstWhatItTakes() {
        SQLException error = error("CALL move_shard('docs', 'first', 11, 12)");

        assertEquals("22023", error.getSQLState());
        assertEquals("Argument 'shard_id' of move_shard takes a whole number of 0 or more, not "
            + "'first'", error.getMessage());
        assertEquals("Argument 'collection' of move_shard takes text, not 7",
            error("CALL move_shard(7, 0, 11, 12)").getMessage());
        assertEquals("Argument 'force' of remove_peer takes true or false, not 'yes'",
            error("CALL remove_peer(7, force => 'yes')").getMessage());
        assertEquals("Argument 'placement' of create_shard_key takes JSON text, not '[1,'",
            error("CALL create_shard_key('docs', 'eu', placement => '[1,')").getMessage());
        assertEquals("Argument 'shard_key' of delete_shard_key takes text or a whole number, not "
            + "true", error("CALL delete_shard_key('docs', true)").getMessage());
    }

    @Test
    void plansASnapshotOfACollectionAShardAndTheWholeStorage() throws SQLException {
        ProcedurePlan create = plan("CALL create_snapshot('my docs')");
        assertEquals(HttpMethod.POST, create.method());
        assertEquals("/collections/my%20docs/snapshots", create.path());
        assertNull(create.body());
        assertNull(create.file());
        assertNull(create.restores());

        assertEquals("/collections/docs/snapshots?wait=false",
            plan("CALL create_snapshot('docs', wait => false)").path());
        assertEquals("/collections/docs/shards/3/snapshots",
            plan("CALL create_shard_snapshot('docs', 3)").path());
        assertEquals("/snapshots", plan("CALL create_full_snapshot()").path());

        ProcedurePlan delete = plan("CALL delete_snapshot('docs', 'a b.snapshot')");
        assertEquals(HttpMethod.DELETE, delete.method());
        assertEquals("/collections/docs/snapshots/a%20b.snapshot", delete.path());
        assertEquals("/collections/docs/shards/0/snapshots/s.snapshot?wait=true",
            plan("CALL delete_shard_snapshot('docs', 0, 's.snapshot', wait => true)").path());
        assertEquals("/snapshots/full.snapshot",
            plan("CALL delete_full_snapshot('full.snapshot')").path());
    }

    @Test
    void sendsWhereQdrantFindsASnapshotToRecoverFrom() throws SQLException {
        ProcedurePlan recover = plan("CALL recover_snapshot('docs', "
            + "'https://other:6333/collections/docs/snapshots/s.snapshot', "
            + "priority => 'snapshot', checksum => 'abc', api_key => 'k', wait => false)");

        assertEquals(HttpMethod.PUT, recover.method());
        assertEquals("/collections/docs/snapshots/recover?wait=false", recover.path());
        assertEquals("{\"location\":\"https://other:6333/collections/docs/snapshots/s.snapshot\","
            + "\"priority\":\"snapshot\",\"checksum\":\"abc\",\"api_key\":\"k\"}",
            recover.body().toString());
        assertEquals("docs", recover.restores(), "the collection's columns may change");
        assertNull(recover.file());

        ProcedurePlan shard = plan("CALL recover_shard_snapshot('docs', 2, 's.snapshot')");
        assertEquals("/collections/docs/shards/2/snapshots/recover", shard.path());
        assertEquals("{\"location\":\"s.snapshot\"}", shard.body().toString());
        assertEquals("docs", shard.restores());
    }

    @Test
    void keepsTheFileOfADownloadOutOfTheRequest() throws SQLException {
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "docs.snapshot")
            .toAbsolutePath().normalize();

        ProcedurePlan download = plan("CALL download_snapshot('docs', 's.snapshot', '" + file
            + "')");
        assertEquals(Transfer.DOWNLOAD, download.procedure().transfer());
        assertEquals(HttpMethod.GET, download.method());
        assertEquals("/collections/docs/snapshots/s.snapshot", download.path());
        assertNull(download.body());
        assertEquals(file, download.file());
        assertNull(download.restores());
        assertTrue(download.changesData(), "a read-only connection refuses it");

        assertEquals("/collections/docs/shards/1/snapshots/s.snapshot", plan(
            "CALL download_shard_snapshot('docs', 1, 's.snapshot', path => '" + file + "')")
            .path());
        assertEquals("/collections/docs/shards/1/snapshot",
            plan("CALL stream_shard_snapshot('docs', 1, '" + file + "')").path());
        assertEquals("/snapshots/full.snapshot",
            plan("CALL download_full_snapshot('full.snapshot', '" + file + "')").path());
    }

    @Test
    void sendsAnUploadsOptionsInTheQueryString() throws SQLException {
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "docs.snapshot")
            .toAbsolutePath().normalize();

        ProcedurePlan upload = plan("CALL upload_snapshot('docs', '" + file
            + "', priority => 'snapshot', checksum => 'abc', wait => false)");
        assertEquals(Transfer.UPLOAD, upload.procedure().transfer());
        assertEquals(HttpMethod.POST, upload.method());
        assertEquals("/collections/docs/snapshots/upload?priority=snapshot&checksum=abc"
            + "&wait=false", upload.path());
        assertNull(upload.body());
        assertEquals(file, upload.file());
        assertEquals("docs", upload.restores());

        ProcedurePlan shard = plan("CALL upload_shard_snapshot('docs', 4, '" + file + "')");
        assertEquals("/collections/docs/shards/4/snapshots/upload", shard.path());
        assertEquals(file, shard.file());
    }

    @Test
    void takesOnlyAFilesFullPath() {
        SQLException error = error("CALL download_snapshot('docs', 's.snapshot', 'docs.snapshot')");

        assertEquals("22023", error.getSQLState());
        assertEquals("Argument 'path' of download_snapshot takes the file's full path, not "
            + "'docs.snapshot'", error.getMessage());
        assertEquals("download_snapshot needs 'path': download_snapshot(collection, snapshot, "
            + "path)", error("CALL download_snapshot('docs', 's.snapshot')").getMessage());
    }

    @Test
    void movesAFileOnlyInTheSixProceduresThatSayTheyDo() {
        for (Procedure procedure : Procedure.values()) {
            boolean named = procedure.procedureName().startsWith("download_")
                || procedure.procedureName().startsWith("upload_")
                || procedure == Procedure.STREAM_SHARD_SNAPSHOT;

            assertEquals(named, procedure.transfer() != Transfer.NONE, procedure.procedureName());
        }
    }

    private ProcedurePlan plan(String sql) throws SQLException {
        return (ProcedurePlan) planner.plan(Parser.parse(sql), NO_LOOKUPS);
    }

    private SQLException error(String sql) {
        return assertThrows(SQLException.class, () -> plan(sql));
    }
}
