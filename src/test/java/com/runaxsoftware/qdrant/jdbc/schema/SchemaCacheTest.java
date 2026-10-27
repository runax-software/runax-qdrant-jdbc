package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.runaxsoftware.qdrant.jdbc.gateway.models.QdrantCollection;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class SchemaCacheTest {

    private static final Duration EXPIRY = Duration.ofMinutes(10);
    private static final int THREADS = 8;

    private final AtomicLong now = new AtomicLong(-5);
    private final AtomicInteger reads = new AtomicInteger();
    private final SchemaCache cache = new SchemaCache(EXPIRY, now::get);

    // Keeping and expiring

    @Test
    void keepsASchemaUntilItExpires() throws SQLException {
        TableSchema first = cache.get("docs", this::read);

        now.addAndGet(EXPIRY.toNanos() - 1);
        assertSame(first, cache.get("docs", this::read));

        now.incrementAndGet();
        assertNotSame(first, cache.get("docs", this::read));
        assertEquals(2, reads.get());
    }

    @Test
    void keepsEachCollectionApart() throws SQLException {
        assertEquals("docs", cache.get("docs", this::read).name());
        assertEquals("notes", cache.get("notes", this::read).name());
        assertEquals("docs", cache.get("docs", this::read).name());

        assertEquals(2, reads.get());
    }

    @Test
    void keepsNothingWithAZeroExpiry() throws SQLException {
        SchemaCache off = new SchemaCache(Duration.ZERO, now::get);

        off.get("docs", this::read);
        off.get("docs", this::read);

        assertEquals(2, reads.get());
    }

    @Test
    void forgetsOnlyTheCollectionsThatMatch() throws SQLException {
        TableSchema docs = cache.get("docs", this::read);
        TableSchema notes = cache.get("notes", this::read);

        cache.forget(name -> name.startsWith("d"));

        assertNotSame(docs, cache.get("docs", this::read));
        assertSame(notes, cache.get("notes", this::read));
        assertEquals(3, reads.get());
    }

    // Failures

    @Test
    void keepsNoFailedRead() throws SQLException {
        SQLException missing = new SQLException("Collection docs doesn't exist", "42S02");

        assertSame(missing, assertThrows(SQLException.class,
            () -> cache.get("docs", name -> {
                throw missing;
            })));

        assertEquals("docs", cache.get("docs", this::read).name());
        assertEquals(1, reads.get());
    }

    @Test
    void passesUncheckedFailuresOn() {
        IllegalStateException bug = new IllegalStateException("bug");

        assertSame(bug, assertThrows(IllegalStateException.class,
            () -> cache.get("docs", name -> {
                throw bug;
            })));
    }

    @Test
    void stopsWaitingWhenInterruptedButLetsTheReadFinish() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService thread = Executors.newSingleThreadExecutor();

        try {
            Future<TableSchema> reading = thread.submit(() -> cache.get("docs",
                name -> slowRead(name, started, release)));
            await(started);

            Thread.currentThread().interrupt();
            SQLException error = assertThrows(SQLException.class,
                () -> cache.get("docs", this::read));
            assertEquals("HY008", error.getSQLState());
            assertTrue(Thread.interrupted(), "keeps the interrupt");

            release.countDown();
            assertSame(reading.get(5, TimeUnit.SECONDS), cache.get("docs", this::read));
            assertEquals(1, reads.get());
        } finally {
            thread.shutdownNow();
        }
    }

    // Threads

    @Test
    void sharesOneReadBetweenThreads() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(THREADS);

        try {
            List<Future<TableSchema>> results = new ArrayList<>();

            for (int i = 0; i < THREADS; i++) {
                results.add(threads.submit(() -> cache.get("docs",
                    name -> slowRead(name, started, release))));
            }

            await(started);
            release.countDown();

            for (Future<TableSchema> result : results) {
                assertSame(results.get(0).get(5, TimeUnit.SECONDS),
                    result.get(5, TimeUnit.SECONDS));
            }

            assertEquals(1, reads.get());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void doesNotKeepAReadForgottenWhileUnderWay() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService thread = Executors.newSingleThreadExecutor();

        try {
            Future<TableSchema> early = thread.submit(() -> cache.get("docs",
                name -> slowRead(name, started, release)));
            await(started);

            cache.forget(name -> true);
            TableSchema fresh = cache.get("docs", this::read);
            release.countDown();

            assertNotSame(fresh, early.get(5, TimeUnit.SECONDS));
            assertSame(fresh, cache.get("docs", this::read));
            assertEquals(2, reads.get());
        } finally {
            thread.shutdownNow();
        }
    }

    /**
     * A new schema for each read, told apart by its point count.
     */
    private TableSchema read(String collection) {
        QdrantCollection qdrantCollection =
            new QdrantCollection(collection, reads.incrementAndGet(), List.of(), Map.of());

        return new TableSchema(qdrantCollection, List.of());
    }

    private TableSchema slowRead(String collection, CountDownLatch started,
                                 CountDownLatch release) {
        started.countDown();
        await(release);

        return read(collection);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
