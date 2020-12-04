package com.composum.sling.platform.testing.testutil;

import com.google.common.collect.Iterators;
import org.apache.sling.api.SlingHttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * A {@link ResourceBundle} implementation for use in tests. That's a little difficult because
 * {@link ResourceBundle} contains many final methods.
 */
public class MockResourceBundle extends ResourceBundle {

    protected Map values;

    protected MockResourceBundle() {
        values = Collections.synchronizedMap(new HashMap<>());
    }

    protected MockResourceBundle(Map map) {
        values = Collections.synchronizedMap(map);
    }

    @Override
    protected Object handleGetObject(@NotNull String key) {
        return values.get(key);
    }

    @NotNull
    @Override
    public Enumeration<String> getKeys() {
        return Iterators.asEnumeration(values.keySet().iterator());
    }

    /** Adds a mapping; returns this bundle for builder style chaining. */
    public MockResourceBundle add(String key, Object value) {
        values.put(key, value);
        return this;
    }

    /** Adds these keys with these values to the map (specify as key1, value1, key2, value2, ...). */
    public MockResourceBundle add(Object... keysAndValues) {
        for (int i = 0; i < keysAndValues.length; i = i + 2) {
            add((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return this;
    }

    /** Returns a bundle that maps these keys to these values (specify as key1, value1, key2, value2, ...). */
    public static MockResourceBundle of(Object... keysAndValues) {
        return new MockResourceBundle().add(keysAndValues);
    }

    /** Presents this map as a ResourceBundle. */
    public static MockResourceBundle of(Map<String, ?> map) {
        return new MockResourceBundle(map);
    }

    /**
     * Adds this bundle as representation for all locales to the (Mockito) mock of a request. If more detailed
     * mocking is required (different languages, ...) you can mock
     * {@link SlingHttpServletRequest#getResourceBundle(String, Locale)} yourself.
     */
    public static MockResourceBundle forRequestMock(SlingHttpServletRequest request) {
        MockResourceBundle result = new MockResourceBundle();
        Mockito.when(request.getResourceBundle(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(result);
        Mockito.when(request.getResourceBundle(ArgumentMatchers.any())).thenReturn(result);
        return result;
    }

}
