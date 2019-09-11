package com.composum.sling.platform.testing.testutil;

import org.hamcrest.Matcher;
import org.junit.internal.AssumptionViolatedException;
import org.junit.rules.ErrorCollector;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

import javax.annotation.Nonnull;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Extends JUnit's {@link org.junit.rules.ErrorCollector} so that it also prints failed checks even when we have an exception in the test, since these might contain
 * important information about the why of the exception. All throwables are wrapped into a {@link MultipleFailureException} if neccesary.
 * <p>
 * This implements {@link MethodRule} instead of {@link org.junit.rules.TestRule} since they have precedence and we want e.g. to print the JCR content
 * on failures before the SlingContext rule shuts the JCR down.
 */
public class ErrorCollectorAlwaysPrintingFailures implements MethodRule {

    @Nonnull
    protected List<TestingRunnableWithException> onFailureActions = new ArrayList<>();

    protected final InternalErrorCollector errorCollector = new InternalErrorCollector();

    @Override
    public Statement apply(Statement base, FrameworkMethod method, Object target) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                List<Throwable> failures = new ArrayList<>();
                Throwable thrown = null;
                try {
                    base.evaluate();
                } catch (UndeclaredThrowableException e) {
                    if (e.getCause() instanceof MultipleFailureException) {
                        MultipleFailureException mfe = (MultipleFailureException) e.getCause();
                        failures.addAll(mfe.getFailures());
                    } else
                        thrown = e.getCause();
                } catch (AssumptionViolatedException e) {
                    throw e;
                } catch (Throwable t) {
                    thrown = t;
                }

                if (thrown != null)
                    failures.add(thrown); // add it at the start

                try { // add recorded failures
                    errorCollector.verify();
                } catch (MultipleFailureException e) {
                    failures.addAll(e.getFailures());
                } catch (Throwable tv) {
                    failures.add(tv);
                }

                if (thrown != null)
                    failures.add(thrown); // add it at the end, too, so we see it immediately no matter where we look

                if (!failures.isEmpty())
                    runOnFailures(failures);

                if (failures.size() == 2 && thrown == failures.get(0) && thrown == failures.get(1))
                    throw cleanStacktrace(failures.get(0)); // don't wrap thrown if there are no other failures.

                failures = failures.stream().map(ErrorCollectorAlwaysPrintingFailures.this::cleanStacktrace).collect(Collectors.toList());
                MultipleFailureException.assertEmpty(failures);
            }
        };
    }

    // removes this class from the stacktrace since it's annoying - it always invites to click on it.
    protected Throwable cleanStacktrace(Throwable throwable) {
        List<StackTraceElement> stacktrace = Arrays.asList(throwable.getStackTrace());
        stacktrace = stacktrace.stream().filter(el ->
                !el.getClassName().contains(getClass().getName()) // there are inner classes of this, too.
        ).collect(Collectors.toList());
        throwable.setStackTrace(stacktrace.toArray(new StackTraceElement[0]));
        return throwable;
    }

    /**
     * Adds to the table the exception, if any, thrown from {@code callable}.
     * Execution continues, but the test will fail at the end if
     * {@code runnable} threw an exception and {@code exceptionMatcher} does not accept that.
     */
    public void checkFailsWith(Callable<?> callable, Matcher<Throwable> exceptionMatcher) {
        try {
            callable.call();
            errorCollector.checkThat(null, exceptionMatcher);
        } catch (Throwable e) {
            errorCollector.checkThat(e, exceptionMatcher);
        }
    }

    /**
     * Adds to the table the exception, if any, thrown from {@code runnable}.
     * Execution continues, but the test will fail at the end if
     * {@code runnable} threw an exception and {@code exceptionMatcher} does not accept that.
     */
    public void checkFailsWith(TestingRunnableWithException runnable, Matcher<Throwable> exceptionMatcher) {
        try {
            runnable.run();
            errorCollector.checkThat(null, exceptionMatcher);
        } catch (Throwable e) {
            errorCollector.checkThat(e, exceptionMatcher);
        }
    }

    /**
     * Adds a Throwable to the table.  Execution continues, but the test will fail at the end.
     */
    public void addError(Throwable error) {
        errorCollector.addError(error);
    }

    /**
     * Adds a failure to the table if {@code matcher} does not match {@code value}.
     * Execution continues, but the test will fail at the end if the match fails.
     *
     * @return the value
     */
    public <T> T checkThat(T value, Matcher<T> matcher) {
        return checkThat("", value, matcher);
    }

    /**
     * Adds a failure with the given {@code reason}
     * to the table if {@code matcher} does not match {@code value}.
     * Execution continues, but the test will fail at the end if the match fails.
     *
     * @return the value
     */
    public <T> T checkThat(String reason, T value, Matcher<T> matcher) {
        errorCollector.checkThat(reason, value, matcher);
        return value;
    }

    /**
     * Checks that the application of {function} to the {value} matches the {matcher}, swallowing exceptions.
     * Execution continues, but the test will fail at the end if the match fails.
     * Use this when it's possible that the execution throws up but the test shall continue, anyway.
     *
     * @return the result of the application of {function} to {value}, or null if it throws
     */
    public <T, U, E extends Throwable> U checkAppliedThat(
            T value, TestingFunctionWithException<T, U, E> function, Matcher<U> matcher) {
        return checkAppliedThat("", value, function, matcher);
    }

    /**
     * Checks that the application of {function} to the {value} matches the {matcher}, swallowing exceptions.
     * Execution continues, but the test will fail at the end if the match fails.
     * Use this when it's possible that the execution throws up but the test shall continue, anyway.
     *
     * @return the result of the application of {function} to {value}, or null if it throws
     */
    public <T, U, E extends Throwable> U checkAppliedThat(
            String reason, T value, TestingFunctionWithException<T, U, E> function, Matcher<U> matcher) {
        U res;
        try {
            res = function.apply(value);
        } catch (Throwable e) {
            addError(e);
            return null;
        }
        errorCollector.checkThat(reason, res, matcher);
        return res;
    }

    /**
     * Adds a failure to the table if {@code matcher} does not match the {@code value} returned from the callable.
     * Execution continues, but the test will fail at the end if the match fails.
     * Use this when it's possible that the execution throws up but the test shall continue, anyway.
     *
     * @return the value
     */
    public <T> T checkCallThat(Callable<T> callable, Matcher<T> matcher) {
        return checkCallThat("", callable, matcher);
    }

    /**
     * Adds a failure with the given {@code reason}
     * to the table if {@code matcher} does not match {@code value}.
     * Execution continues, but the test will fail at the end if the match fails.
     *
     * @return the value or null in case of an error
     */
    public <T> T checkCallThat(String reason, Callable<T> callable, Matcher<T> matcher) {
        T res;
        try {
            res = callable.call();
        } catch (Throwable e) {
            addError(e);
            return null;
        }
        errorCollector.checkThat(reason, res, matcher);
        return res;
    }


    /**
     * Adds to the table the exception, if any, thrown from {@code callable}.
     * Execution continues, but the test will fail at the end if
     * {@code callable} threw an exception.
     *
     * @return the result of calling the callable
     */
    public <T> T checkSucceeds(Callable<T> callable) {
        return errorCollector.checkSucceeds(callable);
    }

    /**
     * Register something that should be done on failure - e.g. printing additional debugging information.
     *
     * @return this
     */
    public ErrorCollectorAlwaysPrintingFailures onFailure(TestingRunnableWithException onfailure) {
        this.onFailureActions.add(onfailure);
        return this;
    }

    /**
     * Runs all actions registered with {@link #onFailure(TestingRunnableWithException)} .
     *
     * @param failures here we add any throwables that happen during these actions.
     */
    protected void runOnFailures(List<Throwable> failures) {
        for (TestingRunnableWithException<?> re : onFailureActions) {
            try {
                re.run();
            } catch (Throwable t) {
                failures.add(t);
            }
        }
    }

    /** A runnable that can throw an exception. */
    @FunctionalInterface
    public interface TestingRunnableWithException<E extends Throwable> {
        /** Do something perhaps throwing an exception. */
        void run() throws Throwable;
    }

    /**
     * Function that can throw checked exceptions. You can use this whenever you need a function object for a function that
     * throws checked exceptions - which doesn't fit into {@link java.util.function.Function}.
     */
    @FunctionalInterface
    public interface TestingFunctionWithException<ARG, VALUE, EXCEPTION extends Throwable> {
        /**
         * Applies this function to the given argument.
         *
         * @param t the function argument
         * @return the function result
         */
        VALUE apply(ARG t) throws EXCEPTION;
    }

    protected class InternalErrorCollector extends ErrorCollector {
        @Override
        public void verify() throws Throwable {
            super.verify();
        }
    }
}
