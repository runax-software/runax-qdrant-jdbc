package com.runaxsoftware.qdrant.jdbc.procedure;

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
 * <p>All of them change the cluster, and Qdrant takes them only when it runs as one.
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
        HttpMethod.POST, "/cluster/recover", null);

    /**
     * The schema the procedures are listed in, beside the system tables.
     */
    public static final String SCHEMA = "system";

    private final String procedureName;
    private final String remarks;
    private final HttpMethod method;
    private final String path;
    private final String wrapper;
    private final List<ProcedureArgument> arguments;

    Procedure(String procedureName, String remarks, HttpMethod method, String path,
              String wrapper, ProcedureArgument... arguments) {
        this.procedureName = procedureName;
        this.remarks = remarks;
        this.method = method;
        this.path = path;
        this.wrapper = wrapper;
        this.arguments = List.of(arguments);
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
     * The procedure with this name, ignoring case, or empty when there's none.
     */
    public static Optional<Procedure> named(String name) {
        if (name == null) return Optional.empty();
        String lower = name.toLowerCase(Locale.ROOT);

        return Arrays.stream(values()).filter(procedure -> procedure.procedureName.equals(lower))
            .findFirst();
    }

    private static ProcedureArgument collection() {
        return ProcedureArgument.path("collection", ArgumentKind.TEXT, "The collection");
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

    // The path of the one API that takes every operation on a collection's shards
    private static final class Paths {
        static final String COLLECTION_CLUSTER = "/collections/{collection}/cluster";
    }
}
