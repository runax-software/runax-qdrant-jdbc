package com.runaxsoftware.qdrant.jdbc.schema;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The schemas a {@link SchemaReader} has read, by collection name, each kept until it expires
 * or is forgotten. Safe to use from several threads: threads that ask for the same collection
 * at once share one read, and a read that fails isn't kept, so the next call asks again.
 */
final class SchemaCache {

    /**
     * Reads one collection's schema from Qdrant.
     */
    @FunctionalInterface
    interface Loader {
        /**
         * Reads the schema, failing with SQL state {@code 42S02} if there's no such collection.
         */
        TableSchema load(String collection) throws SQLException;
    }

    /**
     * A schema being read or already read, and when its read started.
     */
    private record Entry(FutureTask<TableSchema> read, long startedAt) {
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final long expiryNanos;
    private final LongSupplier nanoTime;

    /**
     * @param expiry how long a schema is kept after it was read; zero keeps nothing
     */
    SchemaCache(Duration expiry) {
        this(expiry, System::nanoTime);
    }

    SchemaCache(Duration expiry, LongSupplier nanoTime) {
        this.expiryNanos = expiry.toNanos();
        this.nanoTime = nanoTime;
    }

    /**
     * The collection's schema: the kept one if it hasn't expired, otherwise read with
     * {@code loader}, which runs on the calling thread.
     */
    TableSchema get(String collection, Loader loader) throws SQLException {
        if (expiryNanos == 0) return loader.load(collection);

        long now = nanoTime.getAsLong();
        Entry created = new Entry(new FutureTask<>(() -> loader.load(collection)), now);
        Entry entry = entries.compute(collection,
            (name, current) -> current != null && fresh(current, now) ? current : created);

        if (entry == created) created.read().run();

        return await(collection, entry);
    }

    /**
     * Drops the schemas of the collections that match, so the next call reads them again.
     * A read already under way still answers the threads waiting for it.
     */
    void forget(Predicate<String> collections) {
        entries.keySet().removeIf(collections);
    }

    private boolean fresh(Entry entry, long now) {
        return now - entry.startedAt() < expiryNanos;
    }

    private TableSchema await(String collection, Entry entry) throws SQLException {
        try {
            return entry.read().get();
        } catch (ExecutionException e) {
            entries.remove(collection, entry);
            throw failure(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while reading the columns of " + collection,
                "HY008", e);
        }
    }

    // The loader throws only SQLException, so anything else is unchecked and is thrown as is
    private static SQLException failure(Throwable cause) {
        if (cause instanceof SQLException sql) return sql;
        if (cause instanceof RuntimeException unchecked) throw unchecked;
        if (cause instanceof Error error) throw error;

        return new SQLException(cause);
    }
}
