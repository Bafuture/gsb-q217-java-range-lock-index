package com.example.gsb.rangelock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.NavigableMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class LockedSortedIndexTest {

    private static LockedSortedIndex<Integer, Integer> indexWithKeys(int from, int to) {
        LockedSortedIndex<Integer, Integer> index = new LockedSortedIndex<>();
        for (int i = from; i <= to; i++) {
            index.insert(i, i);
        }
        return index;
    }

    @Test
    void rangeQueryRunsUnderLockAndCountsScans() {
        LockedSortedIndex<Integer, Integer> index = indexWithKeys(1, 10);

        NavigableMap<Integer, Integer> result = index.rangeQuery(2, 6);

        assertThat(result.keySet()).containsExactly(2, 3, 4, 5, 6);
        assertThat(index.stats().scannedEntries()).isEqualTo(5);
        // The lock is released after the query completes.
        assertThat(index.stats().currentLockCount()).isZero();
    }

    @Test
    void updateRangeModifiesValuesAndKeepsIndexOrdered() {
        LockedSortedIndex<Integer, Integer> index = indexWithKeys(1, 10);

        int updated = index.updateRange(3, 6, (key, value) -> value * 100);

        assertThat(updated).isEqualTo(4);
        assertThat(index.keys()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(index.rangeQuery(1, 10).values())
                .containsExactly(1, 2, 300, 400, 500, 600, 7, 8, 9, 10);
    }

    @Test
    void overlappingRangeUpdatesAreSerialized() throws Exception {
        LockedSortedIndex<Integer, Integer> index = indexWithKeys(0, 19);
        BiFunction<Integer, Integer, Integer> slowDoubler = (key, value) -> {
            sleep(30);
            return value * 2;
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long start = System.nanoTime();
            Future<?> first = pool.submit(() -> index.updateRange(0, 9, slowDoubler));
            Future<?> second = pool.submit(() -> index.updateRange(5, 14, slowDoubler));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // Each update touches 10 keys x 30ms = 300ms; overlapping ranges
            // must serialize, so the total cannot be much below 600ms.
            assertThat(elapsedMillis).isGreaterThanOrEqualTo(550);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void disjointRangeUpdatesRunInParallel() throws Exception {
        LockedSortedIndex<Integer, Integer> index = indexWithKeys(0, 19);
        BiFunction<Integer, Integer, Integer> slowDoubler = (key, value) -> {
            sleep(40);
            return value * 2;
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long start = System.nanoTime();
            Future<?> first = pool.submit(() -> index.updateRange(0, 9, slowDoubler));
            Future<?> second = pool.submit(() -> index.updateRange(10, 19, slowDoubler));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // Each update takes ~400ms; disjoint ranges run concurrently, so
            // the total stays well below the ~800ms a serial run would need.
            assertThat(elapsedMillis).isLessThan(700);
            assertThat(index.rangeQuery(0, 19).values())
                    .allSatisfy(value -> assertThat(value % 2).isZero());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pointLockUpgradesToRangeLockThroughFacade() {
        LockedSortedIndex<Integer, Integer> index = indexWithKeys(1, 10);

        var handle = index.lockPoint("owner", 5, Duration.ofSeconds(1));
        index.upgradeToRange(handle, 1, 10, Duration.ofSeconds(1));

        assertThat(handle.from()).isEqualTo(1);
        assertThat(handle.to()).isEqualTo(10);
        // The widened range now excludes other owners.
        assertThatThrownBy(() -> index.lockRange("other", 3, 3, Duration.ofMillis(100)))
                .isInstanceOf(LockTimeoutException.class);
        handle.unlock();
        assertThat(index.stats().currentLockCount()).isZero();
    }

    @Test
    void statsExposeLocksWaitersHoldTimeAndScans() throws Exception {
        LockedSortedIndex<Integer, Integer> index = indexWithKeys(1, 10);
        index.rangeQuery(1, 5); // scans 5 entries

        var held = index.lockRange("A", 0, 10, Duration.ofSeconds(1));
        Thread.sleep(60);
        assertThat(index.stats().currentLockCount()).isEqualTo(1);

        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            var h = index.lockRange("B", 5, 15, Duration.ofSeconds(5));
            h.unlock();
            done.countDown();
        });
        waiter.start();

        long deadline = System.currentTimeMillis() + 2_000;
        while (index.stats().currentWaitingCount() != 1
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(index.stats().currentWaitingCount()).isEqualTo(1);

        held.unlock();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        waiter.join();

        LockedSortedIndex.Stats stats = index.stats();
        assertThat(stats.currentLockCount()).isZero();
        assertThat(stats.currentWaitingCount()).isZero();
        assertThat(stats.completedLockCount()).isGreaterThanOrEqualTo(3);
        assertThat(stats.averageHoldTimeMillis()).isGreaterThan(0.0);
        assertThat(stats.scannedEntries()).isEqualTo(5);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
