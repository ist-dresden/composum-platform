package com.composum.platform.commons.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.Callable;
import java.util.function.Function;

/** Utilities related to exceptions. */
public class ExceptionUtil {

    /**
     * Throws the given exception even if it's a unchecked exception, without needing to declare this.
     * Please use sparingly and with caution and for a good reason, as this is, as it says, sneaky.
     * Do declare the thrown exception on the method that's using this construct inside.
     * You can't catch this exception unless you use catch(Exception) or declare it on a method calling this.
     * You can use it like <code>throw sneakyThrowException(e);</code> to inform the compiler that we
     * never continue.
     *
     * @param exception the exception to throw
     * @return does never return - just for coding clarit
     */
    public static RuntimeException sneakyThrowException(@Nonnull Exception exception) {
        throw ExceptionUtil.<RuntimeException>sneakyThrowExceptionImpl(exception);
    }

    /**
     * Returns a function that calls the original function and might sneakily throw even checked exception without declaring them - for use with {@link java.util.stream.Stream}s.
     * You can (cautiously!) use this with Java 8 style stream methods without using complicated exception handling, but
     * please remember to declare the thrown exceptions on the method!
     * That'd look for example like:
     * <code>list.stream().map(ExceptionUtil.sneakExceptions(Node::getPath).collect(Collectors.toList());</code>
     *
     * @param <T>      the type parameter
     * @param callable the callable
     * @return the result of calling the callable
     */
    @Nonnull
    public static <ARG, VALUE>
    Function<ARG, VALUE> sneakExceptions(@Nonnull final ExceptionThrowingFunction<ARG, VALUE> function) {
        return new Function<ARG, VALUE>() {
            @Override
            public VALUE apply(ARG t) {
                try {
                    return function.apply(t);
                } catch (Exception e) {
                    throw sneakyThrowException(e);
                }
            }
        };
    }

    /**
     * Calls the callable and sneakily throws even checked exception without declaring them.
     * You can (cautiously!) use this with Java 8 style stream methods without using complicated exception handling, but
     * please remember to declare the thrown exceptions on the method!
     * That'd look for example like:
     * <code>list.stream().map((n) -> ExceptionUtil.callAndSneakExceptions(() -> n.getPath())).collect(Collectors.toList());</code>
     *
     * @param <T>      the type parameter
     * @param callable the callable
     * @return the result of calling the callable
     * @see #sneakExceptions(ExceptionThrowingFunction)
     */
    @Nullable
    public static <T> T callAndSneakExceptions(@Nonnull Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw sneakyThrowException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrowExceptionImpl(Throwable exception) throws T {
        throw (T) exception;
    }

    /**
     * Function that can throw exceptions.
     */
    @FunctionalInterface
    public static interface ExceptionThrowingFunction<ARG, VALUE> {
        /**
         * Applies this function to the given argument.
         *
         * @param t the function argument
         * @return the function result
         */
        VALUE apply(ARG t) throws Exception;
    }

}
