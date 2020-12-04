package com.composum.platform.commons.util;

/**
 * Runnable that can throw checked exceptions. You can use this whenever you need a function object for a runnable that
 * throws checked exceptions - which doesn't fit into {@link Runnable}.
 */
@FunctionalInterface
public interface ExceptionThrowingRunnable<EXCEPTION extends Throwable> {

    /** Executes the actions of this. */
    void run() throws EXCEPTION;

}
