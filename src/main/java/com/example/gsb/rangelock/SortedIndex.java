package com.example.gsb.rangelock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;

/**
 * A key-ordered, thread-safe index backed by a {@link ConcurrentSkipListMap}.
 * Keys and values must be non-null. Range bounds are inclusive on both ends.
 *
 * <p>The index itself provides no mutual exclusion between range operations;
 * use {@link LockedSortedIndex} (or a {@link RangeLockManager}) for that.
 */
public final class SortedIndex<K extends Comparable<K>, V> {

    private final ConcurrentSkipListMap<K, V> map = new ConcurrentSkipListMap<>();
    private final LongAdder scannedEntries = new LongAdder();

    /** Inserts or replaces the value for {@code key}; returns the previous value or {@code null}. */
    public V insert(K key, V value) {
        return map.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    /** Removes {@code key}; returns the removed value or {@code null}. */
    public V delete(K key) {
        return map.remove(Objects.requireNonNull(key, "key"));
    }

    /** Point lookup by key; returns {@code null} when absent. */
    public V get(K key) {
        return map.get(Objects.requireNonNull(key, "key"));
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    /** All keys in ascending order. */
    public List<K> keys() {
        return new ArrayList<>(map.keySet());
    }

    /**
     * Returns a snapshot of all entries with {@code from <= key <= to},
     * ordered by key. Adds the number of matched entries to the scan counter.
     */
    public NavigableMap<K, V> rangeQuery(K from, K to) {
        checkRange(from, to);
        NavigableMap<K, V> snapshot = new TreeMap<>(map.subMap(from, true, to, true));
        scannedEntries.add(snapshot.size());
        return snapshot;
    }

    /**
     * Applies {@code updater} to every entry with {@code from <= key <= to}.
     * Keys are never modified, so index ordering is preserved by construction.
     *
     * @return the number of updated entries
     */
    public int updateRange(K from, K to, BiFunction<? super K, ? super V, ? extends V> updater) {
        checkRange(from, to);
        Objects.requireNonNull(updater, "updater");
        int updated = 0;
        for (Map.Entry<K, V> entry : map.subMap(from, true, to, true).entrySet()) {
            V newValue = Objects.requireNonNull(
                    updater.apply(entry.getKey(), entry.getValue()),
                    "updater must not return null");
            map.put(entry.getKey(), newValue);
            updated++;
        }
        scannedEntries.add(updated);
        return updated;
    }

    /** Total number of entries scanned by range queries and range updates so far. */
    public long scannedEntries() {
        return scannedEntries.sum();
    }

    public void resetScannedEntries() {
        scannedEntries.reset();
    }

    private void checkRange(K from, K to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.compareTo(to) > 0) {
            throw new IllegalArgumentException("from must be <= to: [" + from + ", " + to + "]");
        }
    }
}
