package com.example.gsb.rangelock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NavigableMap;
import org.junit.jupiter.api.Test;

class SortedIndexTest {

    @Test
    void insertGetDeleteAndKeysStayOrdered() {
        SortedIndex<Integer, String> index = new SortedIndex<>();
        index.insert(5, "five");
        index.insert(1, "one");
        index.insert(9, "nine");
        index.insert(3, "three");

        assertThat(index.keys()).containsExactly(1, 3, 5, 9);
        assertThat(index.get(3)).isEqualTo("three");
        assertThat(index.get(4)).isNull();
        assertThat(index.size()).isEqualTo(4);

        assertThat(index.delete(5)).isEqualTo("five");
        assertThat(index.keys()).containsExactly(1, 3, 9);
    }

    @Test
    void insertReplacesExistingValue() {
        SortedIndex<Integer, String> index = new SortedIndex<>();
        index.insert(1, "old");
        assertThat(index.insert(1, "new")).isEqualTo("old");
        assertThat(index.get(1)).isEqualTo("new");
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void rangeQueryReturnsInclusiveBoundsInKeyOrder() {
        SortedIndex<Integer, String> index = new SortedIndex<>();
        for (int i = 1; i <= 10; i++) {
            index.insert(i, "v" + i);
        }

        NavigableMap<Integer, String> result = index.rangeQuery(3, 6);

        assertThat(result.keySet()).containsExactly(3, 4, 5, 6);
        assertThat(result.values()).containsExactly("v3", "v4", "v5", "v6");
        // Bounds outside the data clamp to existing keys.
        assertThat(index.rangeQuery(0, 100).keySet())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(index.rangeQuery(50, 60)).isEmpty();
    }

    @Test
    void rangeUpdateModifiesValuesAndKeepsIndexOrdered() {
        SortedIndex<Integer, Integer> index = new SortedIndex<>();
        for (int i = 1; i <= 10; i++) {
            index.insert(i, i);
        }

        int updated = index.updateRange(3, 6, (key, value) -> value * 10);

        assertThat(updated).isEqualTo(4);
        assertThat(index.keys()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(index.rangeQuery(1, 10).values())
                .containsExactly(1, 2, 30, 40, 50, 60, 7, 8, 9, 10);
    }

    @Test
    void invertedRangeIsRejected() {
        SortedIndex<Integer, Integer> index = new SortedIndex<>();
        assertThatThrownBy(() -> index.rangeQuery(5, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.updateRange(5, 1, (k, v) -> v))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scannedEntriesAreCounted() {
        SortedIndex<Integer, Integer> index = new SortedIndex<>();
        for (int i = 1; i <= 10; i++) {
            index.insert(i, i);
        }

        index.rangeQuery(1, 5);   // 5 entries
        index.rangeQuery(8, 20);  // 3 entries
        index.updateRange(2, 3, (k, v) -> v); // 2 entries

        assertThat(index.scannedEntries()).isEqualTo(10);

        index.resetScannedEntries();
        assertThat(index.scannedEntries()).isZero();
    }
}
