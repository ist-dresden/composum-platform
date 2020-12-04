package com.composum.platform.commons.util;

/**
 * Consumer that can throw checked exceptions. You can use this whenever you need a consumer object that
 * throws checked exceptions - which doesn't fit into {@link java.util.function.Consumer}.
 */
@FunctionalInterface
public interface ExceptionThrowingConsumer<ARG, EXCEPTION extends Throwable> {

    /**
     * Performs this operation on the given argument.
     */
    void apply(ARG t) throws EXCEPTION;
}
