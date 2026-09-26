package com.example.gsb.rangelock;

import java.time.Duration;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Combines a {@link SortedIndex} with a {@link RangeLockManager}: range
 * queries and range updates run under an exclusive range lock so concurrent
 * operations on overlapping ranges cannot interfere, while disjoint ranges
 * proceed in parallel.
 *
 * <p>Single-key operations ({@link #insert}, {@link #delete}, {@link #get})
 * are lock-free; the underlying index is thread-safe. The implicit owner of
 * lock-protected operations is the calling thread.
 */
public final class LockedSortedIndex<K extends Comparable<K>, V> {

    private final SortedIndex<K, V> index = new SortedIndex<>();
    private final RangeLockManager<K> lockManager = new RangeLockManager<>();
    private final Duration defaultLockTimeout;

    public LockedSortedIndex() {
        this(Duration.ofSeconds(10));
    }

    public LockedSortedIndex(Duration defaultLockTimeout) {
        this.defaultLockTimeout = Objects.requireNonNull(defaultLockTimeout, "defaultLockTimeout");
    }

    // ------------------------------------------------------------------
    // Lock-free single-key index operations
    // ------------------------------------------------------------------

    public V insert(K key, V value) {
        return index.insert(key, value);
    }

    public V delete(K key) {
        return index.delete(key);
    }

    public V get(K key) {
        return index.get(key);
    }

    public int size() {
        return index.size();
    }

    public List<K> keys() {
        return index.keys();
    }

    // ------------------------------------------------------------------
    // Lock-protected range operations
    // ------------------------------------------------------------------

    /** Runs a range query under an exclusive lock on {@code [from, to]}. */
    public NavigableMap<K, V> rangeQuery(K from, K to) {
        try (RangeLockManager.LockHandle<K> ignored =
                     lockManager.lock(Thread.currentThread(), from, to, defaultLockTimeout)) {
            return index.rangeQuery(from, to);
        }
    }

    /**
     * Applies {@code updater} to every entry in {@code [from, to]} while
     * holding an exclusive lock on that range. Keys are never modified, so
     * index ordering is preserved.
     *
     * @return the number of updated entries
     */
    public int updateRange(K from, K to, BiFunction<? super K, ? super V, ? extends V> updater) {
        try (RangeLockManager.LockHandle<K> ignored =
                     lockManager.lock(Thread.currentThread(), from, to, defaultLockTimeout)) {
            return index.updateRange(from, to, updater);
        }
    }

    // ------------------------------------------------------------------
    // Explicit lock control (e.g. for lock-upgrade flows)
    // ------------------------------------------------------------------

    /** Acquires a point lock on a single key. */
    public RangeLockManager.LockHandle<K> lockPoint(Object owner, K key, Duration timeout) {
        return lockManager.lock(owner, key, key, timeout);
    }

    /** Acquires a range lock on {@code [from, to]}. */
    public RangeLockManager.LockHandle<K> lockRange(Object owner, K from, K to, Duration timeout) {
        return lockManager.lock(owner, from, to, timeout);
    }

    /**
     * Upgrades a held (point) lock to a wider range. Atomic: the original
     * range stays held while waiting, and is kept unchanged on timeout.
     */
    public RangeLockManager.LockHandle<K> upgradeToRange(
            RangeLockManager.LockHandle<K> handle, K from, K to, Duration timeout) {
        return lockManager.upgrade(handle, from, to, timeout);
    }

    public RangeLockManager<K> lockManager() {
        return lockManager;
    }

    public SortedIndex<K, V> index() {
        return index;
    }

    /** Combined snapshot of lock and index statistics. */
    public Stats stats() {
        RangeLockManager.LockStats lockStats = lockManager.stats();
        return new Stats(
                lockStats.currentLockCount(),
                lockStats.currentWaitingCount(),
                lockStats.completedLockCount(),
                lockStats.averageHoldTimeMillis(),
                index.scannedEntries());
    }

    /** Immutable snapshot of combined statistics. */
    public record Stats(int currentLockCount,
                        int currentWaitingCount,
                        long completedLockCount,
                        double averageHoldTimeMillis,
                        long scannedEntries) {
    }
}
