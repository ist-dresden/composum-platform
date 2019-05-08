package com.composum.sling.platform.testing.testutil;

import org.hamcrest.Matcher;
import org.junit.runner.Description;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Extends JUnit's {@link org.junit.rules.ErrorCollector} so that it also prints failed checks even when we have an exception in the test, since these might contain
 * important information about the why of the exception. All throwables are wrapped into a {@link MultipleFailureException} if neccesary.
 */
public class ErrorCollectorAlwaysPrintingFailures extends org.junit.rules.ErrorCollector {

    @Nonnull
    protected List<RunnableWithException> onFailureActions = new ArrayList<>();

    @Override
    public Statement apply(final Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Throwable thrown = null;
                try {
                    base.evaluate();
                } catch (Throwable t) {
                    thrown = t;
                }

                List<Throwable> failures = new ArrayList<>();

                if (thrown != null)
                    failures.add(thrown); // add it at the start

                try { // add recorded failures
                    verify();
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
            checkThat(null, exceptionMatcher);
        } catch (Throwable e) {
            checkThat(e, exceptionMatcher);
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
            checkThat(null, exceptionMatcher);
        } catch (Throwable e) {
            checkThat(e, exceptionMatcher);
        }
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


}
