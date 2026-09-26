package com.example.gsb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LockedSortedIndexTest {

    private final LockedSortedIndex<Integer, Integer> db = new LockedSortedIndex<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void rangeUpdateUnderLockKeepsIndexSorted() throws Exception {
        for (int i = 0; i < 20; i++) {
            db.put(i, i);
        }
        int updated = db.updateRangeLocked(5, 14, Duration.ofSeconds(5), v -> v * 10);

        assertThat(updated).isEqualTo(10);
        assertThat(db.index().keys()).isSorted();
        assertThat(db.range(0, 19)).extracting(Map.Entry::getValue)
                .isEqualTo(List.of(0, 1, 2, 3, 4, 50, 60, 70, 80, 90,
                        100, 110, 120, 130, 140, 15, 16, 17, 18, 19));
        // Lock released after the update.
        assertThat(db.lockManager().activeLockCount()).isZero();
    }

    @Test
    void concurrentRangeUpdatesOnDisjointRangesBothApply() throws Exception {
        for (int i = 0; i < 100; i++) {
            db.put(i, 0);
        }
        CountDownLatch bothLocked = new CountDownLatch(2);
        Future<?> a = pool.submit(() -> guardedUpdate(0, 49, 1, bothLocked));
        Future<?> b = pool.submit(() -> guardedUpdate(50, 99, 2, bothLocked));

        assertThat(bothLocked.await(5, TimeUnit.SECONDS)).isTrue();
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);

        assertThat(db.range(0, 49)).allSatisfy(e -> assertThat(e.getValue()).isEqualTo(1));
        assertThat(db.range(50, 99)).allSatisfy(e -> assertThat(e.getValue()).isEqualTo(2));
    }

    private void guardedUpdate(int from, int to, int value, CountDownLatch bothLocked) {
        try (LockHandle<Integer> h = db.lockManager().lock(from, to, Duration.ofSeconds(10))) {
            bothLocked.countDown();
            bothLocked.await(5, TimeUnit.SECONDS);
            db.index().updateRange(from, to, v -> value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void rangeUpdateTimesOutWhenRangeIsLocked() throws Exception {
        db.put(1, 10);
        LockHandle<Integer> held = db.lockManager().lock(0, 100, Duration.ofSeconds(10));
        try {
            assertThatThrownBy(() -> db.updateRangeLocked(1, 1, Duration.ofMillis(100), v -> v + 1))
                    .isInstanceOf(LockTimeoutException.class);
            // Timed-out update must not have modified anything.
            assertThat(db.get(1)).isEqualTo(10);
        } finally {
            held.unlock();
        }
    }

    @Test
    void statsExposeLocksWaitersHoldTimeAndScans() throws Exception {
        for (int i = 0; i < 10; i++) {
            db.put(i, i);
        }
        LockStats empty = db.stats();
        assertThat(empty.activeLocks()).isZero();
        assertThat(empty.waitingThreads()).isZero();
        assertThat(empty.averageHoldTimeMillis()).isZero();
        assertThat(empty.scannedKeys()).isZero();

        LockHandle<Integer> held = db.lockManager().lock(0, 4, Duration.ofSeconds(10));
        assertThat(db.stats().activeLocks()).isEqualTo(1);

        // A blocked waiter shows up in the stats.
        CountDownLatch waiting = new CountDownLatch(1);
        Future<?> waiter = pool.submit(() -> {
            waiting.countDown();
            try (LockHandle<Integer> h = db.lockManager().lock(2, 3, Duration.ofSeconds(10))) {
                // acquired after release below
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiting.await(5, TimeUnit.SECONDS);
        Thread.sleep(100); // let the waiter block
        assertThat(db.stats().waitingThreads()).isEqualTo(1);

        Thread.sleep(50); // give the held lock a measurable hold time
        held.unlock();
        waiter.get(5, TimeUnit.SECONDS);

        db.range(0, 4);   // scans 5
        db.range(7, 9);   // scans 3

        LockStats stats = db.stats();
        assertThat(stats.activeLocks()).isZero();
        assertThat(stats.waitingThreads()).isZero();
        assertThat(stats.averageHoldTimeMillis()).isGreaterThan(0.0);
        assertThat(stats.scannedKeys()).isEqualTo(8);
    }
}
