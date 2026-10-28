package com.runaxsoftware.qdrant.jdbc.schema;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * One answer from Qdrant, such as the collection list, kept until it expires or is read again.
 * Safe to use from several threads, like {@link SchemaCache}: threads that ask at once share
 * one read, and a read that fails isn't kept, so the next call asks again.
 *
 * @param <T> what is kept; it must not change once read
 */
final class Snapshot<T> {

    /**
     * Asks Qdrant for the answer.
     */
    @FunctionalInterface
    interface Loader<T> {
        /**
         * Reads the answer from Qdrant.
         */
        T load() throws SQLException;
    }

    /**
     * An answer being read or already read, and when its read started.
     */
    private record Entry<T>(FutureTask<T> read, long startedAt) {
    }

    private final AtomicReference<Entry<T>> kept = new AtomicReference<>();
    private final String name;
    private final Loader<T> loader;
    private final long expiryNanos;
    private final LongSupplier nanoTime;

    /**
     * @param name   what is kept, such as {@code "the collection list"}, for error messages
     * @param loader asks Qdrant for it, on the calling thread
     * @param expiry how long it is kept after it was read; zero keeps nothing
     */
    Snapshot(String name, Loader<T> loader, Duration expiry) {
        this(name, loader, expiry, System::nanoTime);
    }

    Snapshot(String name, Loader<T> loader, Duration expiry, LongSupplier nanoTime) {
        this.name = name;
        this.loader = loader;
        this.expiryNanos = expiry.toNanos();
        this.nanoTime = nanoTime;
    }

    /**
     * The kept answer if it hasn't expired, otherwise a new one, read and kept.
     */
    T get() throws SQLException {
        if (expiryNanos == 0) return loader.load();

        long now = nanoTime.getAsLong();
        Entry<T> created = entry(now);
        Entry<T> entry = kept.updateAndGet(
            current -> current != null && now - current.startedAt() < expiryNanos
                ? current : created);

        if (entry == created) created.read().run();

        return await(entry);
    }

    /**
     * Reads the answer from Qdrant whether or not one is kept, and keeps it in place of the
     * old one.
     */
    T refresh() throws SQLException {
        if (expiryNanos == 0) return loader.load();

        Entry<T> created = entry(nanoTime.getAsLong());
        kept.set(created);
        created.read().run();

        return await(created);
    }

    private Entry<T> entry(long now) {
        return new Entry<>(new FutureTask<>(loader::load), now);
    }

    private T await(Entry<T> entry) throws SQLException {
        try {
            return entry.read().get();
        } catch (ExecutionException e) {
            kept.compareAndSet(entry, null);
            throw SchemaCache.failure(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while reading " + name, "HY008", e);
        }
    }
}
