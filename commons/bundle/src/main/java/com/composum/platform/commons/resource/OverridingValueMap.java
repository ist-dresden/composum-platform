package com.composum.platform.commons.resource;

import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * a ValueMap wrapper which is overriding some values by another value map
 */
public class OverridingValueMap extends ValueMapDecorator {

    protected final ValueMap overrides;

    /**
     * @param base      the 'original' value map
     * @param overrides the values to override the 'original'
     */
    public OverridingValueMap(@Nonnull Map<String, Object> base, @Nonnull ValueMap overrides) {
        super(base);
        this.overrides = overrides;
    }

    @Override
    public <T> T get(@Nonnull String name, @Nonnull Class<T> type) {
        T value = overrides.get(name, type);
        return value != null ? value : super.get(name, type);
    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public <T> T get(@Nonnull String name, @Nonnull T defaultValue) {
        T value = get(name, (Class<T>) defaultValue.getClass());
        return value != null ? value : super.get(name, defaultValue);
    }

    @Override
    public int size() {
        return keySet().size();
    }

    @Override
    public boolean isEmpty() {
        return overrides.isEmpty() && super.isEmpty();
    }

    @Override
    public boolean containsKey(@Nonnull Object key) {
        return overrides.containsKey(key) || super.containsKey(key);
    }

    @Override
    public boolean containsValue(@Nonnull Object value) {
        return values().contains(value);
    }

    @Override
    public Object get(@Nonnull Object key) {
        Object value = overrides.get(key);
        return value != null ? value : super.get(key);
    }

    @Override
    @Nonnull
    public Set<String> keySet() {
        Set<String> keys = overrides.keySet();
        keys.addAll(super.keySet());
        return keys;
    }

    @Override
    @Nonnull
    public Collection<Object> values() {
        List<Object> values = new ArrayList<>();
        for (String key : keySet()) {
            values.add(get(key));
        }
        return values;
    }

    @Override
    @Nonnull
    public Set<Map.Entry<String, Object>> entrySet() {
        Set<Map.Entry<String, Object>> entries = overrides.entrySet();
        entries.addAll(super.entrySet());
        return entries;
    }
}
