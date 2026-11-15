package com.runaxsoftware.qdrant.jdbc.procedure;

import static com.runaxsoftware.qdrant.jdbc.procedure.ProcedureArgument.file;
import static com.runaxsoftware.qdrant.jdbc.procedure.ProcedureArgument.optional;
import static com.runaxsoftware.qdrant.jdbc.procedure.ProcedureArgument.query;
import static com.runaxsoftware.qdrant.jdbc.procedure.ProcedureArgument.required;

import com.runaxsoftware.qdrant.jdbc.gateway.HttpMethod;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The operations {@code CALL} runs: the things Qdrant does that aren't about rows, and so have
 * no statement of their own. Each is one request, described here whole: its method and path,
 * and where each argument goes in it. {@code DatabaseMetaData} lists them from the same place,
 * so what a tool shows is what runs.
 *
 * <p>The cluster procedures change the cluster, and Qdrant takes them only when it runs as one.
 * The snapshot procedures work on any server. Six of those move a snapshot file between Qdrant
 * and the machine the driver runs on, which a connection allows only when it is opened with
 * {@code localFiles=true}.
 */
public enum Procedure {

    CREATE_SHARD_KEY("create_shard_key",
        "Creates a shard key in a collection with custom sharding, and the shards that hold it",
        HttpMethod.PUT, "/collections/{collection}/shards", null,
        collection(),
        required("shard_key", ArgumentKind.KEY, "The key to create"),
        optional("shards_number", ArgumentKind.WHOLE, "Shards to create for the key"),
        optional("replication_factor", ArgumentKind.WHOLE, "Replicas of each of those shards"),
        optional("placement", ArgumentKind.JSON,
            "The peers the shards may be placed on, as a JSON array of peer ids")),

    DELETE_SHARD_KEY("delete_shard_key",
        "Deletes a shard key, with its shards and the points in them",
        HttpMethod.POST, "/collections/{collection}/shards/delete", null,
        collection(),
        required("shard_key", ArgumentKind.KEY, "The key to delete")),

    MOVE_SHARD("move_shard", "Moves a shard from one peer to another",
        HttpMethod.POST, Paths.COLLECTION_CLUSTER, "move_shard",
        collection(), shard(), fromPeer(), toPeer(), method(false)),

    REPLICATE_SHARD("replicate_shard", "Copies a shard to another peer, keeping the original",
        HttpMethod.POST, Paths.COLLECTION_CLUSTER, "replicate_shard",
        collection(), shard(), fromPeer(), toPeer(), method(false)),

    ABORT_SHARD_TRANSFER("abort_shard_transfer", "Stops a shard transfer that is under way",
        HttpMethod.POST, Paths.COLLECTION_CLUSTER, "abort_transfer",
        collection(), shard(), fromPeer(), toPeer()),

    RESTART_SHARD_TRANSFER("restart_shard_transfer",
        "Starts a shard transfer that is under way again, with the method given",
        HttpMethod.POST, Paths.COLLECTION_CLUSTER, "restart_transfer",
        collection(), shard(), fromPeer(), toPeer(), method(true)),

    DROP_REPLICA("drop_replica", "Deletes one peer's copy of a shard",
        HttpMethod.POST, Paths.COLLECTION_CLUSTER, "drop_replica",
        collection(), shard(),
        required("peer_id", ArgumentKind.WHOLE, "The peer whose copy to delete")),

    START_RESHARDING("start_resharding",
        "Starts changing a collection's number of shards, one shard up or down",
        HttpMethod.POST, Paths.COLLECTION_CLUSTER, "start_resharding",
        collection(),
        required("direction", ArgumentKind.TEXT, "up to add a shard, down to remove one"),
        optional("peer_id", ArgumentKind.WHOLE, "The peer to add the shard on"),
        optional("shard_key", ArgumentKind.KEY, "The shard key to reshard, with custom sharding")),

    ABORT_RESHARDING("abort_resharding", "Stops a resharding that is under way",
        HttpMethod.POST, Paths.COLLECTION_CLUSTER, "abort_resharding",
        collection()),

    REPLICATE_POINTS("replicate_points", "Copies points from one shard key to another",
        HttpMethod.POST, Paths.COLLECTION_CLUSTER, "replicate_points",
        collection(),
        required("from_shard_key", ArgumentKind.KEY, "The shard key to copy points from"),
        required("to_shard_key", ArgumentKind.KEY, "The shard key to copy them to"),
        optional("filter", ArgumentKind.JSON,
            "The points to copy, as a Qdrant filter in JSON; all of them when left out")),

    REMOVE_PEER("remove_peer", "Removes a peer from the cluster",
        HttpMethod.DELETE, "/cluster/peer/{peer_id}", null,
        ProcedureArgument.path("peer_id", ArgumentKind.WHOLE, "The peer to remove"),
        query("force", ArgumentKind.BOOL, "Remove the peer even while it holds shards"),
        query("timeout", ArgumentKind.WHOLE, "Seconds to wait for the cluster to agree")),

    RECOVER_RAFT("recover_raft",
        "Asks the connected peer to recover its Raft state from the other peers",
        HttpMethod.POST, "/cluster/recover", null),

    CREATE_SNAPSHOT("create_snapshot",
        "Takes a snapshot of a collection, which system.snapshots then lists",
        HttpMethod.POST, Paths.SNAPSHOTS, null,
        collection(), waits()),

    DELETE_SNAPSHOT("delete_snapshot", "Deletes a collection's snapshot",
        HttpMethod.DELETE, Paths.SNAPSHOT, null,
        collection(), snapshot(), waits()),

    RECOVER_SNAPSHOT("recover_snapshot",
        "Replaces a collection with a snapshot Qdrant fetches itself, creating the collection "
            + "when there is none",
        HttpMethod.PUT, Paths.SNAPSHOTS + "/recover", null,
        collection(),
        required("location", ArgumentKind.TEXT,
            "Where Qdrant finds the snapshot: an http(s) URL, or a file:// URL on the server"),
        priority(ArgumentTarget.BODY), checksum(ArgumentTarget.BODY), locationKey(), waits()),

    DOWNLOAD_SNAPSHOT("download_snapshot",
        "Saves a collection's snapshot to a file on the machine the driver runs on",
        HttpMethod.GET, Paths.SNAPSHOT, null,
        collection(), snapshot(), saveTo()),

    UPLOAD_SNAPSHOT("upload_snapshot",
        "Replaces a collection with a snapshot file from the machine the driver runs on, "
            + "creating the collection when there is none",
        HttpMethod.POST, Paths.SNAPSHOTS + "/upload", null,
        collection(), readFrom(),
        priority(ArgumentTarget.QUERY), checksum(ArgumentTarget.QUERY), waits()),

    CREATE_SHARD_SNAPSHOT("create_shard_snapshot",
        "Takes a snapshot of one shard, which system.shard_snapshots then lists",
        HttpMethod.POST, Paths.SHARD_SNAPSHOTS, null,
        collection(), shardInPath(), waits()),

    DELETE_SHARD_SNAPSHOT("delete_shard_snapshot", "Deletes a shard's snapshot",
        HttpMethod.DELETE, Paths.SHARD_SNAPSHOT, null,
        collection(), shardInPath(), snapshot(), waits()),

    RECOVER_SHARD_SNAPSHOT("recover_shard_snapshot",
        "Replaces a shard with a snapshot Qdrant fetches itself",
        HttpMethod.PUT, Paths.SHARD_SNAPSHOTS + "/recover", null,
        collection(), shardInPath(),
        required("location", ArgumentKind.TEXT,
            "Where Qdrant finds the snapshot: an http(s) or file:// URL, or the name of one of "
                + "the shard's own snapshots"),
        priority(ArgumentTarget.BODY), checksum(ArgumentTarget.BODY), locationKey(), waits()),

    DOWNLOAD_SHARD_SNAPSHOT("download_shard_snapshot",
        "Saves a shard's snapshot to a file on the machine the driver runs on",
        HttpMethod.GET, Paths.SHARD_SNAPSHOT, null,
        collection(), shardInPath(), snapshot(), saveTo()),

    STREAM_SHARD_SNAPSHOT("stream_shard_snapshot",
        "Takes a snapshot of one shard and saves it to a file on the machine the driver runs "
            + "on, keeping nothing on the server",
        HttpMethod.GET, "/collections/{collection}/shards/{shard_id}/snapshot", null,
        collection(), shardInPath(), saveTo()),

    UPLOAD_SHARD_SNAPSHOT("upload_shard_snapshot",
        "Replaces a shard with a snapshot file from the machine the driver runs on",
        HttpMethod.POST, Paths.SHARD_SNAPSHOTS + "/upload", null,
        collection(), shardInPath(), readFrom(),
        priority(ArgumentTarget.QUERY), checksum(ArgumentTarget.QUERY), waits()),

    CREATE_FULL_SNAPSHOT("create_full_snapshot",
        "Takes a snapshot of the whole storage, which system.snapshots then lists",
        HttpMethod.POST, Paths.FULL_SNAPSHOTS, null,
        waits()),

    DELETE_FULL_SNAPSHOT("delete_full_snapshot", "Deletes a snapshot of the whole storage",
        HttpMethod.DELETE, Paths.FULL_SNAPSHOT, null,
        snapshot(), waits()),

    DOWNLOAD_FULL_SNAPSHOT("download_full_snapshot",
        "Saves a snapshot of the whole storage to a file on the machine the driver runs on",
        HttpMethod.GET, Paths.FULL_SNAPSHOT, null,
        snapshot(), saveTo());

    /**
     * The schema the procedures are listed in, beside the system tables.
     */
    public static final String SCHEMA = "system";

    /**
     * The name of the argument that says which collection a procedure is about.
     */
    public static final String COLLECTION = "collection";

    private final String procedureName;
    private final String remarks;
    private final HttpMethod method;
    private final String path;
    private final String wrapper;
    private final List<ProcedureArgument> arguments;
    private final Transfer transfer;

    Procedure(String procedureName, String remarks, HttpMethod method, String path,
              String wrapper, ProcedureArgument... arguments) {
        this.procedureName = procedureName;
        this.remarks = remarks;
        this.method = method;
        this.path = path;
        this.wrapper = wrapper;
        this.arguments = List.of(arguments);
        this.transfer = transfer(method, this.arguments);
    }

    /**
     * The name a CALL gives, and {@code DatabaseMetaData} lists.
     */
    public String procedureName() {
        return procedureName;
    }

    /**
     * A description shown as the procedure's comment.
     */
    public String remarks() {
        return remarks;
    }

    public HttpMethod method() {
        return method;
    }

    /**
     * The request's path, with {@code {name}} where a path argument goes.
     */
    public String path() {
        return path;
    }

    /**
     * The name Qdrant keeps the body's arguments under, as the collection-cluster API does for
     * each of its operations, or {@code null} when they are the body itself.
     */
    public String wrapper() {
        return wrapper;
    }

    /**
     * The arguments, in the order a call gives them by position.
     */
    public List<ProcedureArgument> arguments() {
        return arguments;
    }

    /**
     * Whether the request moves a file, and which way.
     */
    public Transfer transfer() {
        return transfer;
    }

    /**
     * Whether the request replaces a collection, or a shard of one, with a snapshot, which can
     * create the collection and change its fields and vectors.
     */
    public boolean restores() {
        return this == RECOVER_SNAPSHOT || this == UPLOAD_SNAPSHOT
            || this == RECOVER_SHARD_SNAPSHOT || this == UPLOAD_SHARD_SNAPSHOT;
    }

    /**
     * The procedure with this name, ignoring case, or empty when there's none.
     */
    public static Optional<Procedure> named(String name) {
        if (name == null) return Optional.empty();
        String lower = name.toLowerCase(Locale.ROOT);

        return Arrays.stream(values()).filter(procedure -> procedure.procedureName.equals(lower))
            .findFirst();
    }

    private static ProcedureArgument collection() {
        return ProcedureArgument.path(COLLECTION, ArgumentKind.TEXT, "The collection");
    }

    private static ProcedureArgument shard() {
        return required("shard_id", ArgumentKind.WHOLE, "The shard, as system.shards lists it");
    }

    private static ProcedureArgument fromPeer() {
        return required("from_peer_id", ArgumentKind.WHOLE, "The peer that holds the shard");
    }

    private static ProcedureArgument toPeer() {
        return required("to_peer_id", ArgumentKind.WHOLE, "The peer to send it to");
    }

    private static ProcedureArgument method(boolean required) {
        String remarks = "How to transfer: stream_records, snapshot, or wal_delta";

        return required ? required("method", ArgumentKind.TEXT, remarks)
            : optional("method", ArgumentKind.TEXT, remarks);
    }

    // A request that names a file moves it: Qdrant answers a GET with one, and takes one otherwise
    private static Transfer transfer(HttpMethod method, List<ProcedureArgument> arguments) {
        boolean file = arguments.stream()
            .anyMatch(argument -> argument.target() == ArgumentTarget.FILE);

        if (!file) return Transfer.NONE;

        return method == HttpMethod.GET ? Transfer.DOWNLOAD : Transfer.UPLOAD;
    }

    private static ProcedureArgument shardInPath() {
        return ProcedureArgument.path("shard_id", ArgumentKind.WHOLE,
            "The shard, as system.shards lists it");
    }

    private static ProcedureArgument snapshot() {
        return ProcedureArgument.path("snapshot", ArgumentKind.TEXT,
            "The snapshot's name, as system.snapshots or system.shard_snapshots lists it");
    }

    private static ProcedureArgument saveTo() {
        return file("path", "The file to save the snapshot to; it must not exist yet");
    }

    private static ProcedureArgument readFrom() {
        return file("path", "The snapshot file to send");
    }

    private static ProcedureArgument waits() {
        return query("wait", ArgumentKind.BOOL,
            "false to return at once and let Qdrant finish in the background");
    }

    private static ProcedureArgument priority(ArgumentTarget target) {
        return new ProcedureArgument("priority", ArgumentKind.TEXT, false, target,
            "Which data wins when other replicas hold the collection: replica, snapshot, or "
                + "no_sync");
    }

    private static ProcedureArgument checksum(ArgumentTarget target) {
        return new ProcedureArgument("checksum", ArgumentKind.TEXT, false, target,
            "The snapshot's SHA-256 checksum, which Qdrant checks before recovering");
    }

    private static ProcedureArgument locationKey() {
        return optional("api_key", ArgumentKind.TEXT,
            "The API key Qdrant sends when it fetches the snapshot from a URL. It is written in "
                + "the statement, so a JDBC call log records it");
    }

    // The paths several procedures share
    private static final class Paths {
        // The one API that takes every operation on a collection's shards
        static final String COLLECTION_CLUSTER = "/collections/{collection}/cluster";

        static final String SNAPSHOTS = "/collections/{collection}/snapshots";
        static final String SNAPSHOT = SNAPSHOTS + "/{snapshot}";
        static final String SHARD_SNAPSHOTS = "/collections/{collection}/shards/{shard_id}/snapshots";
        static final String SHARD_SNAPSHOT = SHARD_SNAPSHOTS + "/{snapshot}";
        static final String FULL_SNAPSHOTS = "/snapshots";
        static final String FULL_SNAPSHOT = FULL_SNAPSHOTS + "/{snapshot}";
    }
}
