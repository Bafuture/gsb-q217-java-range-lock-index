package com.example.gsb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interval-based mutual-exclusion lock manager over a sorted key space.
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>Locks cover an inclusive key range {@code [from, to]}; a point lock is
 *       the degenerate range {@code [key, key]}.</li>
 *   <li>Two locks held by different handles conflict iff their ranges overlap.
 *       Non-overlapping locks are granted concurrently (no global serialization).</li>
 *   <li>Lock acquisition is atomic: a request either gets its whole range or
 *       waits. A timed-out request never leaves a partially held lock behind.</li>
 *   <li>Upgrades are supported: a held lock may be widened to a larger range
 *       that contains its current range. The original lock is kept while
 *       waiting; if the upgrade times out the caller still holds the original
 *       (unmodified) lock. Narrowing is rejected.</li>
 * </ul>
 */
public final class RangeLockManager<K extends Comparable<K>> {

    private static final class Entry<K extends Comparable<K>> {
        final LockHandle<K> handle;
        final long acquiredAtNanos;

        Entry(LockHandle<K> handle, long acquiredAtNanos) {
            this.handle = handle;
            this.acquiredAtNanos = acquiredAtNanos;
        }
    }

    private final Object monitor = new Object();
    private final List<Entry<K>> held = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    private int waitingThreads;
    private long releasedLocks;
    private long totalHoldNanos;

    /** Acquires a lock on the inclusive range {@code [from, to]}, waiting up to {@code timeout}. */
    public LockHandle<K> lock(K from, K to, Duration timeout) throws InterruptedException {
        requireOrdered(from, to);
        LockHandle<K> handle = new LockHandle<>(this, ids.incrementAndGet(), from, to);
        synchronized (monitor) {
            awaitUntilFree(handle, from, to, timeout);
            held.add(new Entry<>(handle, System.nanoTime()));
        }
        return handle;
    }

    /** Acquires a point lock on a single key. */
    public LockHandle<K> lockPoint(K key, Duration timeout) throws InterruptedException {
        return lock(key, key, timeout);
    }

    /**
     * Widens a held lock to {@code [newFrom, newTo]}, which must contain the
     * lock's current range. Blocks (still holding the original lock) until
     * overlapping foreign locks are released or {@code timeout} elapses.
     *
     * @throws LockTimeoutException if the widened range could not be obtained in time;
     *         the original lock remains held and unchanged
     * @throws IllegalArgumentException if the new range does not contain the current one
     * @throws IllegalStateException if the handle is not currently held
     */
    public LockHandle<K> upgrade(LockHandle<K> handle, K newFrom, K newTo, Duration timeout)
            throws InterruptedException {
        requireOrdered(newFrom, newTo);
        synchronized (monitor) {
            Entry<K> entry = findEntry(handle);
            if (entry == null) {
                throw new IllegalStateException("lock " + handle + " is not held");
            }
            K curFrom = handle.from();
            K curTo = handle.to();
            if (newFrom.compareTo(curFrom) > 0 || newTo.compareTo(curTo) < 0) {
                throw new IllegalArgumentException(
                        "upgrade range must contain current range [" + curFrom + ", " + curTo + "]");
            }
            awaitUntilFree(handle, newFrom, newTo, timeout);
            handle.reRange(newFrom, newTo);
        }
        return handle;
    }

    /** Releases the lock held by {@code handle}; no-op if it was already released. */
    public void unlock(LockHandle<K> handle) {
        synchronized (monitor) {
            Iterator<Entry<K>> it = held.iterator();
            while (it.hasNext()) {
                Entry<K> e = it.next();
                if (e.handle == handle) {
                    it.remove();
                    releasedLocks++;
                    totalHoldNanos += System.nanoTime() - e.acquiredAtNanos;
                    monitor.notifyAll();
                    return;
                }
            }
        }
    }

    boolean isHeld(LockHandle<K> handle) {
        synchronized (monitor) {
            return findEntry(handle) != null;
        }
    }

    /** Current number of held locks. */
    public int activeLockCount() {
        synchronized (monitor) {
            return held.size();
        }
    }

    /** Current number of threads blocked waiting for a lock. */
    public int waitingThreadCount() {
        synchronized (monitor) {
            return waitingThreads;
        }
    }

    /** Average hold time (ms) over all released locks; {@code 0} if none released yet. */
    public double averageHoldTimeMillis() {
        synchronized (monitor) {
            if (releasedLocks == 0) {
                return 0.0;
            }
            return totalHoldNanos / 1_000_000.0 / releasedLocks;
        }
    }

    private Entry<K> findEntry(LockHandle<K> handle) {
        for (Entry<K> e : held) {
            if (e.handle == handle) {
                return e;
            }
        }
        return null;
    }

    private void awaitUntilFree(LockHandle<K> self, K from, K to, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean registered = false;
        try {
            while (hasConflict(self, from, to)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new LockTimeoutException(
                            "timed out after " + timeout.toMillis() + " ms waiting for range ["
                                    + from + ", " + to + "]");
                }
                if (!registered) {
                    waitingThreads++;
                    registered = true;
                }
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            }
        } finally {
            if (registered) {
                waitingThreads--;
            }
        }
    }

    private boolean hasConflict(LockHandle<K> self, K from, K to) {
        for (Entry<K> e : held) {
            if (e.handle == self) {
                continue;
            }
            if (from.compareTo(e.handle.to()) <= 0 && e.handle.from().compareTo(to) <= 0) {
                return true;
            }
        }
        return false;
    }

    private void requireOrdered(K from, K to) {
        if (from.compareTo(to) > 0) {
            throw new IllegalArgumentException("from must be <= to: " + from + " > " + to);
        }
    }
}
