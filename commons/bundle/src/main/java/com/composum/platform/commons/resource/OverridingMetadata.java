package com.composum.platform.commons.resource;

import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ValueMap;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * a resource metadata wrapper which is overriding some values by a value map
 */
public class OverridingMetadata extends ResourceMetadata {

    protected final ResourceMetadata metadata;
    protected final ValueMap overrides;

    /**
     * @param base      the 'original' value map
     * @param overrides the values to override the 'original'
     */
    public OverridingMetadata(@Nonnull ResourceMetadata base, @Nonnull ValueMap overrides) {
        this.metadata = base;
        this.overrides = overrides;
    }

    @Override
    public int size() {
        return keySet().size();
    }

    @Override
    public boolean isEmpty() {
        return overrides.isEmpty() && metadata.isEmpty();
    }

    @Override
    public boolean containsKey(@Nonnull Object key) {
        return overrides.containsKey(key) || metadata.containsKey(key);
    }

    @Override
    public boolean containsValue(@Nonnull Object value) {
        return values().contains(value);
    }

    @Override
    public Object get(Object key) {
        Object value = overrides.get(key);
        return value != null ? value : metadata.get(key);
    }

    @Override
    @Nonnull
    public Set<String> keySet() {
        Set<String> keys = overrides.keySet();
        keys.addAll(metadata.keySet());
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
        entries.addAll(metadata.entrySet());
        return entries;
    }
}
