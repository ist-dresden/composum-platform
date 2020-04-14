package com.composum.platform.commons.util;

import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Wrapper for an often needed expensive calculation that caches the result for a while and only repeats the
 * calculation on when the value is requested and the cache timed out, and performs the neccesary locking to exclude
 * parallel executions.
 */
@ThreadSafe
public class CachedCalculation<T, EXCEPTION extends Throwable> {

    /** Locked when executing the calculation, to avoid parallel execution. */
    protected final Object lockObject = new Object();

    protected final long timeoutMillis;
    @Nullable
    protected final ExceptionThrowingSupplier<T, EXCEPTION> supplier;

    /** Calculated value and timestamp {@link System#currentTimeMillis()} until this is valid. */
    protected volatile Pair<T, Long> cached;

    /**
     * Sets a timeout and a supplier for the value. The supplier can be set here, or given each time
     * {@link #giveValue(ExceptionThrowingSupplier)}, for instance if it needs resources which are not permanently
     * available.
     *
     * @param timeoutMillis timeout in milliseconds
     * @param supplier      a supplier; if this is null it has to be given in {@link #giveValue(ExceptionThrowingSupplier)}.
     */
    public CachedCalculation(@Nullable ExceptionThrowingSupplier<T, EXCEPTION> supplier, long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        this.supplier = supplier;
    }


    /** Returns the cached value, calculating it if it wasn't calculated yet or timed out. */
    @Nullable
    public T giveValue() throws EXCEPTION {
        return fetchOrCalculateValue(null, false);
    }

    /**
     * Delivers the cached value if there is one - without calculating it.
     *
     * @param ignoreTimeout if true, we also would return a value that is timed out.
     * @return the cached value if there is one
     */
    @Nullable
    public T getCachedValue(boolean ignoreTimeout) {
        Pair<T, Long> thecache = cached;
        T result = null;
        if (thecache != null && (ignoreTimeout || currentTime() < thecache.getRight())) {
            result = thecache.getLeft();
        }
        return result;
    }

    /**
     * Returns the cached value, calculating it if it wasn't calculated yet or timed out.
     *
     * @param currentSupplier the supplier with which this is to be calculated - for instance if there cannot be a
     *                        permanently set supplier since it needs resources not always available. If null, the
     *                        supplier given in the {@link #CachedCalculation(ExceptionThrowingSupplier, long)} is used
     * @param force           if true, the calculation is performed immediately, ignoring a cached value. This can be
     *                        used if the calculation should be done in this thread. Using this instead of
     *                        {@link #invalidate()} means that the cached value can be used by other threads and
     *                        enforces the calculation is done in this thread.
     */
    @Nullable
    public T giveValue(@Nullable ExceptionThrowingSupplier<T, EXCEPTION> currentSupplier, boolean force) throws EXCEPTION {
        return fetchOrCalculateValue(currentSupplier, force);
    }

    protected T fetchOrCalculateValue(@Nullable ExceptionThrowingSupplier<T, EXCEPTION> currentSupplier,
                                      boolean force) throws EXCEPTION {
        Pair<T, Long> thecache = cached;
        if (!force) {
            if (thecache != null && currentTime() < thecache.getRight()) { return thecache.getLeft(); }
        }

        synchronized (lockObject) {
            Pair<T, Long> thecache2 = cached;
            if (thecache2 != thecache) { // might have been recalculated until we hit this
                if (thecache2 != null && currentTime() < thecache2.getRight()) { return thecache2.getLeft(); }
            }

            cached = Pair.of(calculateValue(currentSupplier), currentTime() + getTimeoutMillis());
            return cached.getLeft();
        }
    }

    protected T calculateValue(@Nullable ExceptionThrowingSupplier<T, EXCEPTION> currentSupplier) throws EXCEPTION {
        @Nonnull
        ExceptionThrowingSupplier<T, EXCEPTION> usedSupplier;
        if (currentSupplier != null) {
            usedSupplier = currentSupplier;
        } else if (supplier != null) {
            usedSupplier = supplier;
        } else {
            throw new IllegalArgumentException("If no permanent supplier is set, a supplier needs to be " +
                    "passed.");
        }
        return usedSupplier.get();
    }

    /**
     * Invalidates the current value, enforcing a new calculation. Use when it is known that the value is wrong. If
     * requests during the new calculation should still get the cached value, consider
     * {@link #giveValue(ExceptionThrowingSupplier, boolean)} with force=true.
     */
    public void invalidate() {
        cached = null;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    protected long currentTime() {
        return System.currentTimeMillis();
    }

}
