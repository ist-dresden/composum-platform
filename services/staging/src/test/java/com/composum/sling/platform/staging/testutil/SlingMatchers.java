package com.composum.sling.platform.staging.testutil;

import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.collections4.ListUtils;
import org.apache.sling.api.resource.Resource;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Some extensions for hamcrest {@link org.hamcrest.Matchers}. */
public class SlingMatchers extends org.hamcrest.Matchers {

    /**
     * Creates a matcher that matches if a resource's path is as given.
     * <p>
     * <p/>
     * For example:
     * <pre>assertThat("myValue", allOf(startsWith("my"), containsString("Val")))</pre>
     */
    @Nonnull
    public static Matcher<Resource> hasResourcePath(@Nullable String path) {
        return new BaseMatcher<Resource>() {
            @Override
            public boolean matches(Object item) {
                return (item instanceof Resource) && path.equals(((Resource) item).getPath());
            }

            @Override
            public void describeMismatch(Object item, Description description) {
                if (item == null) {
                    super.describeMismatch(item, description);
                } else if (item instanceof Resource) {
                    Resource resource = (Resource) item;
                    description.appendText("was ").appendValue(item.getClass()).appendText(" with path ").appendText(resource.getPath());
                } else {
                    description.appendText("was of class ").appendValue(item.getClass());
                }
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("expected to be Resource with path " + path);
            }
        };
    }

    /** Matcher that passes the item through a function {mapper} and then does the matching with {matcher}. */
    @Nonnull
    public static <U, V> Matcher<V> mappedMatches(@Nonnull final Function<V, U> mapper, @Nonnull final Matcher<U> matcher) {
        return new BaseMatcher<V>() {
            @Override
            public boolean matches(Object item) {
                U mapped = mapper.apply((V) item);
                return matcher.matches(mapped);
            }

            @Override
            public void describeMismatch(Object item, Description description) {
                U mapped = mapper.apply((V) item);
                matcher.describeMismatch(mapped, description);
            }

            @Override
            public void describeTo(Description description) {
                matcher.describeTo(description);
            }
        };
    }

    @Nonnull
    public static <T> Matcher<Iterator<T>> iteratorWithSize(int size) {
        return SlingMatchers.mappedMatches(IteratorUtils::toList, Matchers.iterableWithSize(size));
    }

    @Nonnull
    public static <U, V> Matcher<Map<U, V>> hasMapSize(int size) {
        return mappedMatches(Map::size, is(size));
    }

    public static List<String> resourcePaths(Iterable<Resource> resourceList) {
        return IterableUtils.toList(resourceList).stream().map(Resource::getPath).collect(Collectors.toList());
    }

    /**
     * For use with {@link #mappedMatches(Function, Matcher)}: poor mans comparison - sort the map, create a tostring and compare that.
     * Use e.g.
     * <code>SlingMatchers.mappedMatches(SlingMatchers::sortedToString, is("bla=xxx, blu=yyy"));</code>
     */
    @Nonnull
    public static String sortedToString(Map<?, ?> map) {
        SortedMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return sorted.toString();
    }
}
