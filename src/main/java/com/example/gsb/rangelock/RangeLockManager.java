package com.example.gsb.rangelock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Exclusive interval locks over a sorted key space.
 *
 * <p>A lock covers the closed interval {@code [from, to]}. Two locks held by
 * <em>different owners</em> conflict iff their intervals overlap; overlapping
 * requests block until the conflict clears or the timeout expires. Locks whose
 * intervals do not overlap are always granted concurrently. Locks owned by the
 * same owner never conflict with each other.
 *
 * <p>Lock upgrades are supported: an owner holding a (point) lock may widen it
 * to a larger range via {@link #upgrade}. The upgrade is atomic — the original
 * range stays held while waiting, and on timeout the original lock is kept
 * unchanged.
 */
public final class RangeLockManager<K extends Comparable<K>> {

    private final Object monitor = new Object();
    private final List<LockHandle<K>> heldLocks = new ArrayList<>();
    private int waitingCount;
    private long completedLockCount;
    private long totalHoldTimeNanos;

    /** Acquires a lock on {@code [from, to]}, waiting indefinitely. */
    public LockHandle<K> lock(Object owner, K from, K to) {
        return lockInternal(owner, from, to, null);
    }

    /**
     * Acquires a lock on {@code [from, to]}, waiting at most {@code timeout}.
     *
     * @throws LockTimeoutException if the lock is not granted in time; no
     *                              partial lock state is left behind
     */
    public LockHandle<K> lock(Object owner, K from, K to, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        return lockInternal(owner, from, to, timeout.toNanos());
    }

    private LockHandle<K> lockInternal(Object owner, K from, K to, Long timeoutNanos) {
        Objects.requireNonNull(owner, "owner");
        checkRange(from, to);
        synchronized (monitor) {
            awaitGrant(owner, from, to, timeoutNanos);
            LockHandle<K> handle = new LockHandle<>(this, owner, from, to);
            heldLocks.add(handle);
            return handle;
        }
    }

    /**
     * Widens (or moves) an already held lock to {@code [newFrom, newTo]}.
     * The original lock remains fully held while waiting for the new range;
     * if the wait times out the original lock is kept unchanged.
     *
     * @throws IllegalStateException if the handle was already released
     * @throws LockTimeoutException  if the new range cannot be granted in time
     */
    public LockHandle<K> upgrade(LockHandle<K> handle, K newFrom, K newTo, Duration timeout) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(timeout, "timeout");
        checkRange(newFrom, newTo);
        synchronized (monitor) {
            if (handle.released) {
                throw new IllegalStateException("cannot upgrade a released lock");
            }
            awaitGrant(handle.owner, newFrom, newTo, timeout.toNanos());
            handle.from = newFrom;
            handle.to = newTo;
            return handle;
        }
    }

    /** Snapshot of lock statistics. */
    public LockStats stats() {
        synchronized (monitor) {
            double avgMillis = completedLockCount == 0
                    ? 0.0
                    : totalHoldTimeNanos / 1_000_000.0 / completedLockCount;
            return new LockStats(heldLocks.size(), waitingCount, completedLockCount, avgMillis);
        }
    }

    private void release(LockHandle<K> handle) {
        synchronized (monitor) {
            if (handle.released) {
                return; // idempotent
            }
            if (!heldLocks.remove(handle)) {
                throw new IllegalStateException("handle not held by this manager");
            }
            handle.released = true;
            completedLockCount++;
            totalHoldTimeNanos += System.nanoTime() - handle.acquiredAtNanos;
            monitor.notifyAll();
        }
    }

    /** Must be called with {@code monitor} held. */
    private void awaitGrant(Object owner, K from, K to, Long timeoutNanos) {
        long deadline = timeoutNanos == null ? 0L : System.nanoTime() + timeoutNanos;
        while (conflictsLocked(owner, from, to)) {
            waitingCount++;
            try {
                if (timeoutNanos == null) {
                    monitor.wait();
                } else {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        throw new LockTimeoutException(
                                "timed out waiting for range lock [" + from + ", " + to + "]");
                    }
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockTimeoutException(
                        "interrupted while waiting for range lock [" + from + ", " + to + "]", e);
            } finally {
                waitingCount--;
            }
        }
    }

    /** Must be called with {@code monitor} held. */
    private boolean conflictsLocked(Object owner, K from, K to) {
        for (LockHandle<K> held : heldLocks) {
            if (held.owner.equals(owner)) {
                continue; // same owner never conflicts with itself
            }
            if (overlaps(from, to, held.from, held.to)) {
                return true;
            }
        }
        return false;
    }

    private static <T extends Comparable<T>> boolean overlaps(T fromA, T toA, T fromB, T toB) {
        return fromA.compareTo(toB) <= 0 && toA.compareTo(fromB) >= 0;
    }

    private void checkRange(K from, K to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.compareTo(to) > 0) {
            throw new IllegalArgumentException("from must be <= to: [" + from + ", " + to + "]");
        }
    }

    /** Immutable snapshot of lock-manager statistics. */
    public record LockStats(int currentLockCount,
                            int currentWaitingCount,
                            long completedLockCount,
                            double averageHoldTimeMillis) {
    }

    /** A granted range lock. {@link #unlock()} (or try-with-resources) releases it. */
    public static final class LockHandle<K extends Comparable<K>> implements AutoCloseable {

        private final RangeLockManager<K> manager;
        private final Object owner;
        private final long acquiredAtNanos = System.nanoTime();
        private K from;
        private K to;
        private boolean released;

        private LockHandle(RangeLockManager<K> manager, Object owner, K from, K to) {
            this.manager = manager;
            this.owner = owner;
            this.from = from;
            this.to = to;
        }

        public Object owner() {
            return owner;
        }

        public K from() {
            synchronized (manager.monitor) {
                return from;
            }
        }

        public K to() {
            synchronized (manager.monitor) {
                return to;
            }
        }

        public boolean isReleased() {
            synchronized (manager.monitor) {
                return released;
            }
        }

        /** Releases the lock. Idempotent: releasing twice is a no-op. */
        public void unlock() {
            manager.release(this);
        }

        @Override
        public void close() {
            unlock();
        }
    }
}
