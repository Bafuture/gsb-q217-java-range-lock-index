package com.example.gsb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A key-sorted index backed by a {@link TreeMap}.
 *
 * <p>Supports insert, delete, point lookup and inclusive range queries.
 * Every range query / range update records how many entries it scanned so the
 * metric can be exposed through {@link LockedSortedIndex#stats()}.</p>
 *
 * <p>All methods are thread-safe; callers that need cross-call mutual
 * exclusion over a key range should use {@link RangeLockManager} (or the
 * {@link LockedSortedIndex} facade).</p>
 */
public final class SortedIndex<K extends Comparable<K>, V> {

    private final NavigableMap<K, V> map = new TreeMap<>();
    private final AtomicLong scannedKeys = new AtomicLong();

    /** Inserts or replaces the value for {@code key}; returns the previous value or {@code null}. */
    public synchronized V put(K key, V value) {
        return map.put(key, value);
    }

    /** Removes {@code key}; returns the removed value or {@code null}. */
    public synchronized V remove(K key) {
        return map.remove(key);
    }

    /** Point lookup by key. */
    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized boolean containsKey(K key) {
        return map.containsKey(key);
    }

    public synchronized int size() {
        return map.size();
    }

    /**
     * Returns a snapshot of all entries with {@code from <= key <= to} in key order.
     * The number of matched entries is added to the scanned-keys metric.
     */
    public synchronized List<Map.Entry<K, V>> range(K from, K to) {
        requireOrdered(from, to);
        List<Map.Entry<K, V>> result = new ArrayList<>();
        for (Map.Entry<K, V> e : map.subMap(from, true, to, true).entrySet()) {
            result.add(Map.entry(e.getKey(), e.getValue()));
        }
        scannedKeys.addAndGet(result.size());
        return result;
    }

    /**
     * Applies {@code updater} to the value of every key in {@code [from, to]}.
     * Keys themselves are never modified, so index ordering is preserved by construction.
     *
     * @return number of entries updated
     */
    public synchronized int updateRange(K from, K to, Function<V, V> updater) {
        requireOrdered(from, to);
        int count = 0;
        for (Map.Entry<K, V> e : map.subMap(from, true, to, true).entrySet()) {
            e.setValue(updater.apply(e.getValue()));
            count++;
        }
        scannedKeys.addAndGet(count);
        return count;
    }

    /** Returns all keys in ascending order (snapshot). */
    public synchronized List<K> keys() {
        return new ArrayList<>(map.keySet());
    }

    /** Total number of entries scanned by range queries and range updates so far. */
    public long scannedKeyCount() {
        return scannedKeys.get();
    }

    private void requireOrdered(K from, K to) {
        if (from.compareTo(to) > 0) {
            throw new IllegalArgumentException("from must be <= to: " + from + " > " + to);
        }
    }
}
