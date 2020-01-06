package com.composum.platform.commons.util;

/**
 * Supplier that can throw checked exceptions. You can use this whenever you need a consumer object that
 * throws checked exceptions - which doesn't fit into {@link java.util.function.Supplier}.
 */
@FunctionalInterface
public interface ExceptionThrowingSupplier<T, EXCEPTION extends Throwable> {

    /**
     * Gets a result.
     *
     * @return a result
     */
    T get() throws EXCEPTION;

}
