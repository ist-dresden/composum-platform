package com.composum.sling.platform.staging.testutil;

import org.hamcrest.Matcher;
import org.mockito.Matchers;

/**
 * Just extends the mockito {@link Matchers} so that you can use MockitoMatchers{@link #isNotNull()} etc. to avoid naming clashes with
 * the hamcrest matchers. Or just import {@link Matchers#argThat(Matcher)} all the time (maybe statically import that).
 */
public class MockitoMatchers extends Matchers {

    /**
     * Allows creating custom argument matchers.
     * <p>
     * In rare cases when the parameter is a primitive then you <b>*must*</b> use relevant intThat(), floatThat(), etc. method.
     * This way you will avoid <code>NullPointerException</code> during auto-unboxing.
     * <p>
     * See examples in javadoc for {@link ArgumentMatcher} class
     *
     * @param matcher decides whether argument matches
     * @return <code>null</code>.
     */
    // copied here so that IntelliJ doesn't replace references with references to super class Matchers.
    public static <T> T argThat(Matcher<T> matcher) {
        return Matchers.argThat(matcher);
    }

}
