package com.composum.sling.platform.testing.testutil;

import org.junit.internal.AssumptionViolatedException;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;

/**
 * A rule that allows registering some statements that are executed if the tests fail. This is
 * a {@link MethodRule} since these are executed before e.g. the SlingContext rule, so that we can print stuff
 * before the JCR is cleared.
 */
public class OnFailureRule implements MethodRule {

    @NotNull
    protected List<ErrorCollectorAlwaysPrintingFailures.TestingRunnableWithException> onFailureActions = new ArrayList<>();

    /** Register something that should be done on failure - e.g. printing additional debugging information. */
    public OnFailureRule(ErrorCollectorAlwaysPrintingFailures.TestingRunnableWithException onfailure) {
        this.onFailureActions.add(onfailure);
    }

    @Override
    public Statement apply(Statement base, FrameworkMethod method, Object target) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    base.evaluate();
                } catch (AssumptionViolatedException e) {
                    throw e;
                } catch (Throwable t) {
                    if (t instanceof MultipleFailureException) {
                        MultipleFailureException mf = (MultipleFailureException) t;
                        runOnFailures(mf.getFailures());
                        throw mf;
                    }
                    List<Throwable> failures = new ArrayList<>();
                    failures.add(t);
                    runOnFailures(failures);
                    MultipleFailureException.assertEmpty(failures);
                    throw t;
                }
            }
        };
    }

    /** Register something that should be done on failure - e.g. printing additional debugging information. */
    public OnFailureRule onFailure(ErrorCollectorAlwaysPrintingFailures.TestingRunnableWithException onfailure) {
        this.onFailureActions.add(onfailure);
        return this;
    }

    /**
     * Runs all actions registered with {@link #onFailure(ErrorCollectorAlwaysPrintingFailures.TestingRunnableWithException)} .
     *
     * @param failures here we add any throwables that happen during these actions.
     */
    protected void runOnFailures(List<Throwable> failures) {
        for (ErrorCollectorAlwaysPrintingFailures.TestingRunnableWithException<?> re : onFailureActions) {
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
