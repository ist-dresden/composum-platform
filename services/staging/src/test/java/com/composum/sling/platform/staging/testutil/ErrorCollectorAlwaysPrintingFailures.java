package com.composum.sling.platform.staging.testutil;

import org.hamcrest.Matcher;
import org.junit.runner.Description;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Extends JUnit's {@link org.junit.rules.ErrorCollector} so that it also prints failed checks even when we have an exception in the test, since these might contain
 * important information about the why of the exception. All throwables are wrapped into a {@link MultipleFailureException} if neccesary.
 */
public class ErrorCollectorAlwaysPrintingFailures extends org.junit.rules.ErrorCollector {

    @Override
    public Statement apply(final Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    base.evaluate();
                } catch (Throwable t) {
                    List<Throwable> failures = new ArrayList<>();

                    failures.add(t); // add it at the start

                    try {
                        verify();
                    } catch (MultipleFailureException e) {
                        failures.addAll(e.getFailures());
                    } catch (Throwable tv) {
                        failures.add(tv);
                    }

                    failures.add(t); // add it at the end, too, so we see it immediately no matter where we look

                    if (failures.size() == 2 && t == failures.get(0) && t == failures.get(1))
                        throw failures.get(0); // don't wrap t if there are no other failures.
                    MultipleFailureException.assertEmpty(failures); // always throws since not empty
                    throw t; // impossible, but tell the compiler it ends here.
                }

                verify(); // if test ran through
            }
        };
    }

    /**
     * Adds to the table the exception, if any, thrown from {@code callable}.
     * Execution continues, but the test will fail at the end if
     * {@code callable} threw an exception.
     */
    public void checkFailsWith(Callable<?> callable, Matcher<Throwable> exceptionMatcher) {
        try {
            callable.call();
            checkThat(null, exceptionMatcher);
        } catch (Throwable e) {
            checkThat(e, exceptionMatcher);
        }
    }

}
