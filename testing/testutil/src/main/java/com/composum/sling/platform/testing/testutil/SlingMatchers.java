package com.composum.sling.platform.testing.testutil;

import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.hamcrest.ResourceMatchers;
import org.hamcrest.*;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Some extensions for hamcrest {@link org.hamcrest.Matchers}.
 */
public class SlingMatchers extends org.hamcrest.Matchers {

    /**
     * Creates a matcher that matches if a resource's path is as given.
     * <p>
     * <p/>
     * For example:
     * <pre>assertThat("myValue", allOf(startsWith("my"), containsString("Val")))</pre>
     */
    @NotNull
    public static Matcher<Resource> hasResourcePath(@Nullable String path) {
        return ResourceMatchers.path(path);
    }

    /**
     * Matcher that passes the item through a function {mapper} and then does the matching with {matcher}.
     */
    @NotNull
    public static <U, V> Matcher<V> mappedMatches(@Nullable String funcname, @NotNull final Function<V, U> mapper, @NotNull final Matcher<U> matcher) {
        return new BaseMatcher<V>() {
            @Override
            public boolean matches(Object item) {
                U mapped = mapper.apply((V) item);
                return matcher.matches(mapped);
            }

            @Override
            public void describeMismatch(Object item, Description description) {
                U mapped = mapper.apply((V) item);
                if (funcname != null) description.appendText(funcname + " ");
                matcher.describeMismatch(mapped, description);
            }

            @Override
            public void describeTo(Description description) {
                matcher.describeTo(description);
            }
        };
    }

    /**
     * Matches when a resource is a nonexisting resource.
     */
    @NotNull
    public static Matcher<Resource> nonExistingResource() {
        return new BaseMatcher<Resource>() {
            @Override
            public boolean matches(Object item) {
                return (item instanceof Resource) && ResourceUtil.isNonExistingResource((Resource) item);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText(" was not a NonExistingResource");
            }
        };
    }

    /**
     * Matches when a resource is a nonexisting resource with the given path.
     */
    @NotNull
    public static Matcher<Resource> nonExistingResource(@NotNull String path) {
        return Matchers.allOf(ResourceMatchers.resourceType(Resource.RESOURCE_TYPE_NON_EXISTING), hasResourcePath(path));
    }

    /**
     * Matcher that passes the item through a function {mapper} and then does the matching with {matcher}.
     */
    @NotNull
    public static <U, V> Matcher<V> mappedMatches(@NotNull final Function<V, U> mapper, @NotNull final Matcher<U> matcher) {
        return mappedMatches(null, mapper, matcher);
    }

    @NotNull
    public static <T> Matcher<Iterator<T>> iteratorWithSize(int size) {
        return SlingMatchers.mappedMatches("size", IteratorUtils::toList, Matchers.iterableWithSize(size));
    }

    @NotNull
    public static <U, V> Matcher<Map<U, V>> hasMapSize(int size) {
        // return mappedMatches("size", Map::size, is(size));
        return new BaseMatcher<Map<U, V>>() {
            @Override
            public boolean matches(Object item) {
                Map<U, V> map = (Map<U, V>) item;
                return size == map.size();
            }

            @Override
            public void describeMismatch(Object item, Description description) {
                Map<U, V> map = (Map<U, V>) item;
                description.appendText(" was ").appendValue(map.size())
                        .appendText(" because of keys being ").appendValue(map.keySet());
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("is ").appendValue(size);
            }
        };
    }

    /**
     * Introduced to disambiguate between {@link Matchers#hasEntry(Object, Object)} and {@link Matchers#hasEntry(Matcher, Matcher)},
     * which is troublesome if you have a plain Map with various types as entries.
     */
    @NotNull
    public static <K, V> Matcher<java.util.Map> hasEntryMatching(Matcher<? super K> keyMatcher, Matcher<? super V> valueMatcher) {
        return (Matcher) org.hamcrest.collection.IsMapContaining.<K, V>hasEntry(keyMatcher, valueMatcher);
    }

    @NotNull
    public static <T> Matcher<T> stringMatchingPattern(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return new BaseMatcher<T>() {
            @Override
            public boolean matches(Object item) {
                return (item instanceof CharSequence) && pattern.matcher((CharSequence) item).matches();
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("expected to match " + regex);
            }
        };
    }

    @NotNull
    public static <T> Matcher<T> satisfies(@NotNull Predicate<T> predicate) {
        return satisfies("predicate " + predicate, predicate);
    }

    @NotNull
    public static <T> Matcher<T> satisfies(@Nullable String message, @NotNull Predicate<T> predicate) {
        return mappedMatches(message, predicate::test, Matchers.is(true));
    }

    public static Matcher<Throwable> throwableWithMessage(Class<? extends Throwable> clazz, String pattern) {
        final Pattern pat = Pattern.compile(pattern);
        return new CustomTypeSafeMatcher<Throwable>("expected exception with message matching \"" + pattern + "\"") {
            @Override
            protected boolean matchesSafely(Throwable throwable) {
                return pat.matcher(throwable.getMessage()).matches();
            }

            @Override
            protected void describeMismatchSafely(Throwable item, Description mismatchDescription) {
                StringWriter stacktrace = new StringWriter();
                item.printStackTrace(new PrintWriter(stacktrace, true));
                mismatchDescription.appendText("was ").appendValue(item.getMessage())
                        .appendText("\n          Created at: ").appendText(stacktrace.toString());
            }
        };
    }

    /**
     * Returns the exceptions that is thrown by callable, or null if it doesn't throw.
     */
    @Nullable
    public static Throwable exceptionOf(@Nullable Callable<?> callable) {
        try {
            if (callable != null) {
                callable.call();
            }
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * The list of paths of the resources in the list. If a resource is null, we insert a null.
     */
    @NotNull
    public static List<String> resourcePaths(@Nullable Iterable<Resource> resourceList) {
        return resourceList != null ?
                IterableUtils.toList(resourceList).stream().map(SlingResourceUtil::getPath).collect(Collectors.toList())
                : Collections.emptyList();
    }

    /**
     * For use with {@link #mappedMatches(Function, Matcher)}: poor mans comparison - sort the map, create a tostring and compare that.
     * Use e.g.
     * <code>SlingMatchers.mappedMatches(SlingMatchers::sortedToString, is("bla=xxx, blu=yyy"));</code>
     */
    @NotNull
    public static String sortedToString(Map<?, ?> map) {
        SortedMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return sorted.toString();
    }
}
