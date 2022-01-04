package com.composum.platform.commons.util;

import org.slf4j.Logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.Callable;
import java.util.function.Function;

/** Utilities related to exceptions. */
public class ExceptionUtil {

    /**
     * You can use that if you want to log a message and throw an exception with the very same message as an error,
     * to avoid constructing the message twice.
     * <code> throw ExceptionUtil.logAndThrow(new XYException("something is wrong with " + argument), LOG);</code>
     * The throw is not necessary, but informs the compiler about the thrown exception.
     *
     * @param <T>       the exception type
     * @param log       where the exception should be logged
     * @param exception an exception
     * @throws T the exception e is thrown.
     */
    public static <T extends Exception> T logAndThrow(Logger log, T exception) throws T {
        log.error(exception.toString(), exception);
        throw exception;
    }

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
    public static RuntimeException sneakyThrowException(@NotNull Exception exception) {
        throw ExceptionUtil.sneakyThrowExceptionImpl(exception);
    }

    /**
     * Returns a function that calls the original function and might sneakily throw even checked exception without declaring them - for use with {@link java.util.stream.Stream}s.
     * You can (cautiously!) use this with Java 8 style stream methods without using complicated exception handling, but
     * please remember to declare the thrown exceptions on the method!
     * That'd look for example like:
     * <code>list.stream().map(ExceptionUtil.sneakExceptions(Node::getPath).collect(Collectors.toList());</code>
     *
     * @param <ARG>    argument type of the function
     * @param <VALUE>  return type of the function
     * @param function the function to call
     * @return the result of calling the callable
     */
    @NotNull
    public static <ARG, VALUE>
    Function<ARG, VALUE> sneakExceptions(@NotNull final ExceptionThrowingFunction<ARG, VALUE, Exception> function) {
        return t -> {
            try {
                return function.apply(t);
            } catch (Exception e) {
                throw sneakyThrowException(e);
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
    public static <T> T callAndSneakExceptions(@NotNull Callable<T> callable) {
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

}
