package com.composum.platform.commons.util;

/**
 * Function that can throw checked exceptions. You can use this whenever you need a function object for a function that
 * throws checked exceptions - which doesn't fit into {@link java.util.function.Function}.
 */
@FunctionalInterface
public interface ExceptionThrowingFunction<ARG, VALUE, EXCEPTION extends Throwable> {
    /**
     * Applies this function to the given argument.
     *
     * @param t the function argument
     * @return the function result
     */
    VALUE apply(ARG t) throws EXCEPTION;
}
