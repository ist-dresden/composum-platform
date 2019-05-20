package com.composum.sling.platform.testing.testutil;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures.RunnableWithException;
import org.apache.commons.lang3.ClassUtils;
import org.junit.runners.model.MultipleFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Is able to wrap an object such that some configurable actions are done before and after something is called - e.g. committing an ResourceResolver to check for integrity. */
public class AroundActionsWrapper implements InvocationHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AroundActionsWrapper.class);

    private final Object wrappedObject;
    private final RunnableWithException<? extends Throwable> before;
    private final RunnableWithException<? extends Throwable> after;
    private final RunnableWithException<? extends Throwable> onError;

    protected AroundActionsWrapper(Object wrappedObject, RunnableWithException<? extends Throwable> before, RunnableWithException<? extends Throwable> after, RunnableWithException<? extends Throwable> onError) {
        this.wrappedObject = wrappedObject;
        this.before = before;
        this.after = after;
        this.onError = onError;
    }

    /**
     * Wraps an object such that specific actions can be done before and after any method of it is called, such as commiting an ResourceResolver to check for integrity.
     *
     * @param wrappedObject the object
     * @param before        optional action that is done before each call to a method of the object
     * @param after         optional action that is done after each call to a method of the object
     * @param onError       optional action that is run if anything fails - be it the method or the before / after action. E.g. logging of something.
     * @param <T>           the object type
     * @return the wrapped object
     */
    @SuppressWarnings("unchecked")
    @Nonnull
    public static <T> T of(@Nonnull T wrappedObject, @Nullable RunnableWithException<? extends Throwable> before, @Nullable RunnableWithException<? extends Throwable> after, RunnableWithException<? extends Throwable> onError) {
        Class[] interfaces = ClassUtils.getAllInterfaces(wrappedObject.getClass()).toArray(new Class[0]);
        return (T) Proxy.newProxyInstance(wrappedObject.getClass().getClassLoader(), interfaces, new AroundActionsWrapper(wrappedObject, before, after, onError));
    }

    /** Retrieves the object wrapped with {@link #of(Object, RunnableWithException, RunnableWithException)}. */
    @SuppressWarnings("unchecked")
    public static <T> T retrieveWrappedObject(T wrapper) {
        InvocationHandler handler = Proxy.getInvocationHandler(wrapper);
        return (T) ((AroundActionsWrapper) handler).wrappedObject;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        List<Throwable> errors = new ArrayList<>();
        Object returnvalue = null;

        try {
            if (before != null)
                before.run();
        } catch (Throwable t) {
            LOG.error("Error executing before action {}", before, t);
            errors.add(new IllegalStateException("Error executing before action " + before, t));
            runOnError(errors);
        }

        try {
            returnvalue = method.invoke(wrappedObject, args);
        } catch (Throwable t) {
            if (t instanceof InvocationTargetException) {
                t = ((InvocationTargetException) t).getTargetException();
            }
            if (!(t instanceof RuntimeException) && !(t instanceof Error)) {
                boolean found = false;
                for (Class<?> exceptionType : method.getExceptionTypes()) {
                    found = found || exceptionType.isAssignableFrom(t.getClass());
                }
                if (!found) {
                    LOG.error("Method throws a checked exception that isn't actually declared: {}", t.getClass().getName(), t);
                }
            }
            errors.add(t);
            runOnError(errors);
        }

        try {
            if (after != null)
                after.run();
        } catch (Throwable t) {
            LOG.error("Error when executing after action {}", after, t);
            errors.add(new IllegalStateException("Error when executing after action " + after, t));
            runOnError(errors);
        }

        MultipleFailureException.assertEmpty(errors);
        return returnvalue;
    }

    protected void runOnError(List<Throwable> errors) {
        if (onError != null) {
            try {
                onError.run();
            } catch (Throwable te) { // Ugh!
                LOG.error("Error in onError action {}", onError, onError);
                errors.add(new IllegalStateException("Error in onError action " + onError, te));
            }
        }
    }
}
