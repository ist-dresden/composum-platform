package com.composum.platform.commons.util;

import java.util.concurrent.locks.Lock;

/**
 * Allows using a {@link java.util.concurrent.locks.Lock} with a try with resources, e.g.:
 * <code> try (LockAsAutoCloseable locked = LockAsAutoCloseable.lock(lock)) { ... } </code> .
 */
@SuppressWarnings("LockAcquiredButNotSafelyReleased")
public class LockAsAutoCloseable implements AutoCloseable {

    protected final Lock lock;

    protected LockAsAutoCloseable(Lock lock) {
        this.lock = lock;
        lock.lock();
    }

    /**
     * Acquires a lock on the given lock and allows using it the try-with-resources way. For example:
     * <code> try (LockAsAutoCloseable locked = LockAsAutoCloseable.lock(lock)) { ... } </code> .
     * Always use in try-with-resource statement.
     */
    public static LockAsAutoCloseable lock(Lock lock) {
        return new LockAsAutoCloseable(lock);
    }

    /** Unlocks the lock. */
    @Override
    public void close() {
        lock.unlock();
    }
}
