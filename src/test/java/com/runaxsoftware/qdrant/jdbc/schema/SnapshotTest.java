package com.runaxsoftware.qdrant.jdbc.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class SnapshotTest {

    private static final Duration EXPIRY = Duration.ofMinutes(10);
    private static final int THREADS = 8;

    private final AtomicLong now = new AtomicLong(-5);
    private final AtomicInteger reads = new AtomicInteger();
    private final Snapshot<String> snapshot = new Snapshot<>("the list", this::read, EXPIRY,
        now::get);

    // Keeping and expiring

    @Test
    void keepsTheAnswerUntilItExpires() throws SQLException {
        assertEquals("answer 1", snapshot.get());

        now.addAndGet(EXPIRY.toNanos() - 1);
        assertEquals("answer 1", snapshot.get());

        now.incrementAndGet();
        assertEquals("answer 2", snapshot.get());
        assertEquals("answer 2", snapshot.get());
        assertEquals(2, reads.get());
    }

    @Test
    void readsAgainOnRefreshAndKeepsTheNewAnswer() throws SQLException {
        assertEquals("answer 1", snapshot.get());

        assertEquals("answer 2", snapshot.refresh());
        assertEquals("answer 2", snapshot.get());

        now.addAndGet(EXPIRY.toNanos() - 1);
        assertEquals("answer 2", snapshot.get(), "kept from the refresh on");
        assertEquals(2, reads.get());
    }

    @Test
    void keepsNothingWithAZeroExpiry() throws SQLException {
        Snapshot<String> off = new Snapshot<>("the list", this::read, Duration.ZERO, now::get);

        off.get();
        off.refresh();
        off.get();

        assertEquals(3, reads.get());
    }

    // Failures

    @Test
    void keepsNoFailedRead() throws SQLException {
        SQLException down = new SQLException("Qdrant is down", "08S01");
        Snapshot<String> failing = new Snapshot<>("the list", () -> {
            if (reads.incrementAndGet() == 1) throw down;
            return "answer";
        }, EXPIRY, now::get);

        assertSame(down, assertThrows(SQLException.class, failing::get));

        assertEquals("answer", failing.get());
        assertEquals("answer", failing.get());
        assertEquals(2, reads.get());
    }

    @Test
    void dropsTheKeptAnswerWhenARefreshFails() throws SQLException {
        SQLException down = new SQLException("Qdrant is down", "08S01");
        Snapshot<String> failing = new Snapshot<>("the list", () -> {
            if (reads.incrementAndGet() == 2) throw down;
            return "answer " + reads.get();
        }, EXPIRY, now::get);
        failing.get();

        assertSame(down, assertThrows(SQLException.class, failing::refresh));

        assertEquals("answer 3", failing.get());
    }

    @Test
    void reportsAnInterruptedWait() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Snapshot<String> slow = new Snapshot<>("the alias list",
            () -> slowRead(started, release), EXPIRY, now::get);
        ExecutorService thread = Executors.newSingleThreadExecutor();

        try {
            Future<String> reading = thread.submit(slow::get);
            assertTrue(started.await(5, TimeUnit.SECONDS));

            Thread.currentThread().interrupt();
            SQLException error = assertThrows(SQLException.class, slow::get);
            assertEquals("HY008", error.getSQLState());
            assertEquals("Interrupted while reading the alias list", error.getMessage());
            assertTrue(Thread.interrupted(), "keeps the interrupt");

            release.countDown();
            assertEquals(reading.get(5, TimeUnit.SECONDS), slow.get());
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
        Snapshot<String> slow = new Snapshot<>("the list", () -> slowRead(started, release),
            EXPIRY, now::get);
        ExecutorService threads = Executors.newFixedThreadPool(THREADS);

        try {
            List<Future<String>> results = new ArrayList<>();

            for (int i = 0; i < THREADS; i++) {
                results.add(threads.submit(slow::get));
            }

            assertTrue(started.await(5, TimeUnit.SECONDS));
            release.countDown();

            for (Future<String> result : results) {
                assertEquals("answer 1", result.get(5, TimeUnit.SECONDS));
            }

            assertEquals(1, reads.get());
        } finally {
            threads.shutdownNow();
        }
    }

    private String read() {
        return "answer " + reads.incrementAndGet();
    }

    private String slowRead(CountDownLatch started, CountDownLatch release) throws SQLException {
        started.countDown();

        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new SQLException("Never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException(e);
        }

        return read();
    }
}
