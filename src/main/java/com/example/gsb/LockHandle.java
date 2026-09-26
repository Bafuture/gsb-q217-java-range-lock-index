package com.example.gsb;

/**
 * Token representing one held range lock. Obtained from
 * {@link RangeLockManager#lock} / {@link RangeLockManager#lockPoint} and
 * released via {@link RangeLockManager#unlock} or {@link #close()}.
 */
public final class LockHandle<K extends Comparable<K>> implements AutoCloseable {

    private final RangeLockManager<K> manager;
    private final long id;
    private volatile K from;
    private volatile K to;

    LockHandle(RangeLockManager<K> manager, long id, K from, K to) {
        this.manager = manager;
        this.id = id;
        this.from = from;
        this.to = to;
    }

    long id() {
        return id;
    }

    public K from() {
        return from;
    }

    public K to() {
        return to;
    }

    void reRange(K newFrom, K newTo) {
        this.from = newFrom;
        this.to = newTo;
    }

    /** True while this handle still holds its lock. */
    public boolean isHeld() {
        return manager.isHeld(this);
    }

    /** Releases the lock; no-op if already released. */
    public void unlock() {
        manager.unlock(this);
    }

    @Override
    public void close() {
        unlock();
    }

    @Override
    public String toString() {
        return "LockHandle#" + id + "[" + from + ", " + to + "]";
    }
}
