package com.example.gsb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SortedIndexTest {

    private final SortedIndex<Integer, String> index = new SortedIndex<>();

    @Test
    void insertGetAndDelete() {
        assertThat(index.put(3, "c")).isNull();
        index.put(1, "a");
        index.put(2, "b");

        assertThat(index.get(2)).isEqualTo("b");
        assertThat(index.get(99)).isNull();
        assertThat(index.put(2, "b2")).isEqualTo("b");
        assertThat(index.get(2)).isEqualTo("b2");

        assertThat(index.remove(1)).isEqualTo("a");
        assertThat(index.containsKey(1)).isFalse();
        assertThat(index.size()).isEqualTo(2);
    }

    @Test
    void keysAreAlwaysSorted() {
        List.of(5, 1, 9, 3, 7, 2).forEach(k -> index.put(k, "v" + k));
        assertThat(index.keys()).containsExactly(1, 2, 3, 5, 7, 9);
    }

    @Test
    void rangeQueryIsInclusiveAndOrdered() {
        for (int i = 0; i < 10; i++) {
            index.put(i, "v" + i);
        }
        List<Map.Entry<Integer, String>> result = index.range(3, 6);
        assertThat(result).extracting(Map.Entry::getKey).containsExactly(3, 4, 5, 6);
        assertThat(result).extracting(Map.Entry::getValue)
                .containsExactly("v3", "v4", "v5", "v6");

        assertThat(index.range(100, 200)).isEmpty();
        assertThatThrownBy(() -> index.range(6, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rangeQueryCountsScannedKeys() {
        for (int i = 0; i < 10; i++) {
            index.put(i, "v" + i);
        }
        index.range(0, 4); // 5 keys
        index.range(5, 9); // 5 keys
        index.range(50, 60); // 0 keys
        assertThat(index.scannedKeyCount()).isEqualTo(10);
    }
}
