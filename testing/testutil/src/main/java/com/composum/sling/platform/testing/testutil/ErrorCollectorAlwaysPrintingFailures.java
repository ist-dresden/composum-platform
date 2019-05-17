package com.composum.sling.platform.testing.testutil;

import org.hamcrest.Matcher;
import org.junit.internal.AssumptionViolatedException;
import org.junit.rules.ErrorCollector;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Extends JUnit's {@link org.junit.rules.ErrorCollector} so that it also prints failed checks even when we have an exception in the test, since these might contain
 * important information about the why of the exception. All throwables are wrapped into a {@link MultipleFailureException} if neccesary.
 * <p>
 * This implements {@link MethodRule} instead of {@link org.junit.rules.TestRule} since they have precedence and we want e.g. to print the JCR content
 * on failures before the SlingContext rule shuts the JCR down.
 */
public class ErrorCollectorAlwaysPrintingFailures implements MethodRule {

    @Nonnull
    protected List<RunnableWithException> onFailureActions = new ArrayList<>();

    protected final InternalErrorCollector errorCollector = new InternalErrorCollector();

    @Override
    public Statement apply(Statement base, FrameworkMethod method, Object target) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Throwable thrown = null;
                try {
                    base.evaluate();
                } catch (AssumptionViolatedException e) {
                    throw e;
                } catch (Throwable t) {
                    thrown = t;
                }

                List<Throwable> failures = new ArrayList<>();

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
                    throw failures.get(0); // don't wrap thrown if there are no other failures.

                MultipleFailureException.assertEmpty(failures);
            }
        };
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
    public void checkFailsWith(RunnableWithException runnable, Matcher<Throwable> exceptionMatcher) {
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
     */
    public <T> void checkThat(T value, Matcher<T> matcher) {
        errorCollector.checkThat(value, matcher);
    }

    /**
     * Adds a failure with the given {@code reason}
     * to the table if {@code matcher} does not match {@code value}.
     * Execution continues, but the test will fail at the end if the match fails.
     */
    public <T> void checkThat(String reason, T value, Matcher<T> matcher) {
        errorCollector.checkThat(reason, value, matcher);
    }

    /**
     * Adds to the table the exception, if any, thrown from {@code callable}.
     * Execution continues, but the test will fail at the end if
     * {@code callable} threw an exception.
     */
    public <T> T checkSucceeds(Callable<T> callable) {
        return errorCollector.checkSucceeds(callable);
    }

    /** Register something that should be done on failure - e.g. printing additional debugging information. */
    public ErrorCollectorAlwaysPrintingFailures onFailure(RunnableWithException onfailure) {
        this.onFailureActions.add(onfailure);
        return this;
    }

    /**
     * Runs all actions registered with {@link #onFailure(RunnableWithException)} .
     *
     * @param failures here we add any throwables that happen during these actions.
     */
    protected void runOnFailures(List<Throwable> failures) {
        for (RunnableWithException<?> re : onFailureActions) {
            try {
                re.run();
            } catch (Throwable t) {
                failures.add(t);
            }
        }
    }

    /** A runnable that can throw an exception. */
    @FunctionalInterface
    public interface RunnableWithException<E extends Throwable> {
        /** Do something perhaps throwing an exception. */
        void run() throws Throwable;
    }


    protected class InternalErrorCollector extends ErrorCollector {
        @Override
        public void verify() throws Throwable {
            super.verify();
        }
    }
}
