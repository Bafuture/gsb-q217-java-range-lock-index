package com.example.gsb;

/**
 * Immutable snapshot of lock-manager and index metrics.
 *
 * @param activeLocks        number of range locks currently held
 * @param waitingThreads     number of threads currently blocked waiting for a lock
 * @param averageHoldTimeMillis average hold time of all released locks, in milliseconds
 *                              ({@code 0} when no lock has been released yet)
 * @param scannedKeys        total entries scanned by range queries / range updates on the index
 */
public record LockStats(int activeLocks,
                        int waitingThreads,
                        double averageHoldTimeMillis,
                        long scannedKeys) {
}
