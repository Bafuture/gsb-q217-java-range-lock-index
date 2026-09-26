package com.example.gsb;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Facade combining a {@link SortedIndex} with a {@link RangeLockManager} so
 * that range mutations run under the protection of a range lock.
 */
public final class LockedSortedIndex<K extends Comparable<K>, V> {

    private final SortedIndex<K, V> index = new SortedIndex<>();
    private final RangeLockManager<K> lockManager = new RangeLockManager<>();

    public SortedIndex<K, V> index() {
        return index;
    }

    public RangeLockManager<K> lockManager() {
        return lockManager;
    }

    public V put(K key, V value) {
        return index.put(key, value);
    }

    public V remove(K key) {
        return index.remove(key);
    }

    public V get(K key) {
        return index.get(key);
    }

    public List<Map.Entry<K, V>> range(K from, K to) {
        return index.range(from, to);
    }

    /**
     * Acquires the range lock for {@code [from, to]} (waiting up to
     * {@code timeout}), applies {@code updater} to every value in the range,
     * then releases the lock. The lock is always released, even if the
     * updater throws.
     *
     * @return number of entries updated
     */
    public int updateRangeLocked(K from, K to, Duration timeout, Function<V, V> updater)
            throws InterruptedException {
        try (LockHandle<K> ignored = lockManager.lock(from, to, timeout)) {
            return index.updateRange(from, to, updater);
        }
    }

    /** Snapshot of lock and scan statistics. */
    public LockStats stats() {
        return new LockStats(
                lockManager.activeLockCount(),
                lockManager.waitingThreadCount(),
                lockManager.averageHoldTimeMillis(),
                index.scannedKeyCount());
    }
}
