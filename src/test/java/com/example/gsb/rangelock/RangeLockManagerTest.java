package com.example.gsb.rangelock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RangeLockManagerTest {

    @Test
    void overlappingLockRequestsAreMutuallyExclusive() throws Exception {
        RangeLockManager<Integer> manager = new RangeLockManager<>();
        var first = manager.lock("A", 0, 100);

        CountDownLatch acquired = new CountDownLatch(1);
        Thread contender = new Thread(() -> {
            var handle = manager.lock("B", 50, 60, Duration.ofSeconds(5));
            acquired.countDown();
            handle.unlock();
        });
        contender.start();

        Thread.sleep(300);
        assertThat(acquired.getCount()).as("overlapping lock must block").isEqualTo(1);

        first.unlock();
        assertThat(acquired.await(5, TimeUnit.SECONDS))
                .as("lock is granted once the conflicting range is released")
                .isTrue();
        contender.join();
    }

    @Test
    void nonOverlappingLockIsGrantedImmediately() throws Exception {
        RangeLockManager<Integer> manager = new RangeLockManager<>();
        var first = manager.lock("A", 0, 10);

        CountDownLatch acquired = new CountDownLatch(1);
        Thread contender = new Thread(() -> {
            var handle = manager.lock("B", 20, 30, Duration.ofSeconds(2));
            acquired.countDown();
            handle.unlock();
        });
        contender.start();

        assertThat(acquired.await(2, TimeUnit.SECONDS))
                .as("disjoint range must not wait for the held lock")
                .isTrue();
        contender.join();
        first.unlock();
    }

    @Test
    void disjointRangeHoldsOverlapInTime() throws Exception {
        RangeLockManager<Integer> manager = new RangeLockManager<>();
        long holdMillis = 400;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long start = System.nanoTime();
            Future<?> low = pool.submit(() -> hold(manager, "A", 0, 10, holdMillis));
            Future<?> high = pool.submit(() -> hold(manager, "B", 20, 30, holdMillis));
            low.get(5, TimeUnit.SECONDS);
            high.get(5, TimeUnit.SECONDS);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMillis)
                    .as("two disjoint %dms holds must run in parallel (sequential would take >= %dms)",
                            holdMillis, 2 * holdMillis)
                    .isLessThan(750);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void hold(RangeLockManager<Integer> manager, String owner,
                             int from, int to, long holdMillis) {
        var handle = manager.lock(owner, from, to, Duration.ofSeconds(5));
        try {
            Thread.sleep(holdMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            handle.unlock();
        }
    }

    @Test
    void timedOutRequestLeavesNoPartialLock() {
        RangeLockManager<Integer> manager = new RangeLockManager<>();
        var first = manager.lock("A", 0, 100);

        assertThatThrownBy(() -> manager.lock("B", 50, 150, Duration.ofMillis(100)))
                .isInstanceOf(LockTimeoutException.class);

        assertThat(manager.stats().currentLockCount())
                .as("timed-out request must not leave a partial lock")
                .isEqualTo(1);
        assertThat(manager.stats().currentWaitingCount()).isZero();

        first.unlock();
        assertThat(manager.stats().currentLockCount()).isZero();

        // The full range is available again immediately.
        var handle = manager.lock("B", 50, 150, Duration.ofMillis(500));
        handle.unlock();
        assertThat(manager.stats().currentLockCount()).isZero();
    }

    @Test
    void pointLockCanBeUpgradedToRangeLock() {
        RangeLockManager<Integer> manager = new RangeLockManager<>();
        var handle = manager.lock("A", 5, 5);

        manager.upgrade(handle, 1, 10, Duration.ofSeconds(1));

        assertThat(handle.from()).isEqualTo(1);
        assertThat(handle.to()).isEqualTo(10);
        // The widened range is enforced against other owners.
        assertThatThrownBy(() -> manager.lock("B", 3, 3, Duration.ofMillis(100)))
                .isInstanceOf(LockTimeoutException.class);
        handle.unlock();
    }

    @Test
    void conflictingUpgradeTimesOutAndKeepsOriginalLock() {
        RangeLockManager<Integer> manager = new RangeLockManager<>();
        var a = manager.lock("A", 5, 5);
        var b = manager.lock("B", 8, 8);

        assertThatThrownBy(() -> manager.upgrade(a, 1, 10, Duration.ofMillis(100)))
                .isInstanceOf(LockTimeoutException.class);

        // Original lock is retained unchanged and still enforced.
        assertThat(a.from()).isEqualTo(5);
        assertThat(a.to()).isEqualTo(5);
        assertThat(a.isReleased()).isFalse();
        assertThatThrownBy(() -> manager.lock("C", 5, 5, Duration.ofMillis(50)))
                .isInstanceOf(LockTimeoutException.class);

        // Ranges outside both held locks remain available.
        var c = manager.lock("C", 6, 6, Duration.ofMillis(200));
        c.unlock();

        a.unlock();
        b.unlock();
        assertThat(manager.stats().currentLockCount()).isZero();
    }

    @Test
    void statsTrackCurrentLocksWaitersAndHoldTime() throws Exception {
        RangeLockManager<Integer> manager = new RangeLockManager<>();
        var a = manager.lock("A", 0, 10);
        Thread.sleep(50);

        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            var handle = manager.lock("B", 5, 15, Duration.ofSeconds(5));
            handle.unlock();
            done.countDown();
        });
        waiter.start();

        long deadline = System.currentTimeMillis() + 2_000;
        while (manager.stats().currentWaitingCount() != 1
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(manager.stats().currentWaitingCount()).isEqualTo(1);
        assertThat(manager.stats().currentLockCount()).isEqualTo(1);

        a.unlock();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        waiter.join();

        var stats = manager.stats();
        assertThat(stats.currentLockCount()).isZero();
        assertThat(stats.currentWaitingCount()).isZero();
        assertThat(stats.completedLockCount()).isEqualTo(2);
        assertThat(stats.averageHoldTimeMillis()).isGreaterThan(0.0);
    }
}
