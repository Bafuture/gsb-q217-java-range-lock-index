package com.example.gsb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RangeLockManagerTest {

    private static final Duration LONG = Duration.ofSeconds(10);

    private final RangeLockManager<Integer> locks = new RangeLockManager<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void overlappingRangesAreMutuallyExclusive() throws Exception {
        LockHandle<Integer> first = locks.lock(0, 100, LONG);

        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        Future<?> second = pool.submit(() -> {
            try (LockHandle<Integer> h = locks.lock(50, 60, LONG)) {
                int now = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                entered.countDown();
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // While the first lock is held, the overlapping request must not proceed.
        assertThat(entered.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(locks.waitingThreadCount()).isEqualTo(1);

        locks.unlock(first);
        second.get(5, TimeUnit.SECONDS);
        assertThat(maxConcurrent.get()).isEqualTo(1);
        assertThat(locks.activeLockCount()).isZero();
    }

    @Test
    void nonOverlappingRangesRunInParallel() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch bothInside = new CountDownLatch(2);

        Runnable task = () -> {
            // each task locks its own disjoint range via try-lock loop
        };
        Future<?> a = pool.submit(() -> runLocked(0, 49, concurrent, maxConcurrent, bothInside));
        Future<?> b = pool.submit(() -> runLocked(50, 99, concurrent, maxConcurrent, bothInside));

        // If the two disjoint locks were serialized, both threads could never
        // be inside at the same time and this latch would never reach zero.
        assertThat(bothInside.await(5, TimeUnit.SECONDS)).isTrue();
        a.get(5, TimeUnit.SECONDS);
        b.get(5, TimeUnit.SECONDS);
        assertThat(maxConcurrent.get()).isEqualTo(2);
        assertThat(task).isNotNull();
    }

    private void runLocked(int from, int to, AtomicInteger concurrent,
                           AtomicInteger maxConcurrent, CountDownLatch bothInside) {
        try (LockHandle<Integer> h = locks.lock(from, to, LONG)) {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            bothInside.countDown();
            bothInside.await(5, TimeUnit.SECONDS);
            Thread.sleep(50);
            concurrent.decrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void timedOutRequestLeavesNoPartialLock() throws Exception {
        LockHandle<Integer> held = locks.lock(0, 100, LONG);

        long start = System.nanoTime();
        assertThatThrownBy(() -> locks.lock(40, 60, Duration.ofMillis(150)))
                .isInstanceOf(LockTimeoutException.class)
                .hasMessageContaining("timed out");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100);

        // No partial lock left behind: only the original lock remains,
        // the waiter counter is back to zero, and the range frees up normally.
        assertThat(locks.activeLockCount()).isEqualTo(1);
        assertThat(locks.waitingThreadCount()).isZero();

        locks.unlock(held);
        try (LockHandle<Integer> h = locks.lock(40, 60, LONG)) {
            assertThat(locks.activeLockCount()).isEqualTo(1);
        }
        assertThat(locks.activeLockCount()).isZero();
    }

    @Test
    void upgradeWidensPointLockToRange() throws Exception {
        LockHandle<Integer> point = locks.lockPoint(5, LONG);
        locks.upgrade(point, 1, 10, LONG);

        assertThat(point.from()).isEqualTo(1);
        assertThat(point.to()).isEqualTo(10);
        assertThat(point.isHeld()).isTrue();

        // The upgraded range now conflicts with other requests inside it.
        assertThatThrownBy(() -> locks.lock(7, 8, Duration.ofMillis(100)))
                .isInstanceOf(LockTimeoutException.class);
        // ...but not with disjoint ranges.
        try (LockHandle<Integer> other = locks.lock(20, 30, LONG)) {
            assertThat(locks.activeLockCount()).isEqualTo(2);
        }
        locks.unlock(point);
    }

    @Test
    void failedUpgradeKeepsOriginalPointLock() throws Exception {
        LockHandle<Integer> blocker = locks.lock(1, 10, LONG);
        LockHandle<Integer> point = locks.lockPoint(50, LONG);

        // Widening into the blocked range times out; the point lock survives.
        assertThatThrownBy(() -> locks.upgrade(point, 1, 100, Duration.ofMillis(150)))
                .isInstanceOf(LockTimeoutException.class);
        assertThat(point.isHeld()).isTrue();
        assertThat(point.from()).isEqualTo(50);
        assertThat(point.to()).isEqualTo(50);

        // Key 50 is still protected by the original point lock.
        assertThatThrownBy(() -> locks.lockPoint(50, Duration.ofMillis(100)))
                .isInstanceOf(LockTimeoutException.class);

        locks.unlock(point);
        locks.unlock(blocker);
    }

    @Test
    void upgradeRejectsShrinkingRange() throws Exception {
        LockHandle<Integer> h = locks.lock(1, 10, LONG);
        assertThatThrownBy(() -> locks.upgrade(h, 3, 8, LONG))
                .isInstanceOf(IllegalArgumentException.class);
        locks.unlock(h);
    }

    @Test
    void upgradeOfReleasedHandleFails() throws Exception {
        LockHandle<Integer> h = locks.lockPoint(5, LONG);
        locks.unlock(h);
        assertThatThrownBy(() -> locks.upgrade(h, 1, 10, LONG))
                .isInstanceOf(IllegalStateException.class);
    }
}
