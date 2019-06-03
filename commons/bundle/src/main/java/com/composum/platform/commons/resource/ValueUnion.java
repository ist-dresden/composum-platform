package com.composum.platform.commons.resource;

import org.apache.sling.api.resource.ValueMap;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * a union of a set of ValueMaps with a set on top used also as cache of the union values
 */
public class ValueUnion extends ValueSource {

    protected static final Object NULL = "";

    protected final List<ValueMap> cascade = new ArrayList<>();

    public ValueUnion() {
        super(new HashMap<>()); // construction with its own cache
    }

    public ValueUnion(@Nonnull final ValueMap... cascade) {
        this();
        for (ValueMap map : cascade) {
            add(map);
        }
    }

    public void add(int index, @Nonnull final ValueMap map) {
        cascade.add(index, map);
    }

    public void add(@Nonnull final ValueMap map) {
        cascade.add(map);
    }

    public void remove(@Nonnull final ValueMap map) {
        cascade.remove(map);
    }

    public void cacheAll() {
        List<Object> values = new ArrayList<>();
        for (String key : keySet()) {
            get(key);
        }
    }

    @Override
    public int size() {
        return keySet().size();
    }

    @Override
    public boolean isEmpty() {
        for (ValueMap map : cascade) {
            if (!map.isEmpty()) {
                return false;
            }
        }
        return super.isEmpty();
    }

    @Override
    public boolean containsKey(@Nonnull final Object key) {
        for (ValueMap map : cascade) {
            if (map.containsKey(key)) {
                return true;
            }
        }
        return super.containsKey(key);
    }

    @Override
    public boolean containsValue(@Nonnull final Object value) {
        return values().contains(value);
    }

    /**
     * the merging and caching 'get'...
     */
    @Override
    public Object get(@Nonnull Object key) {
        Object value = super.get(key);
        if (value == null) {
            for (ValueMap map : cascade) {
                value = map.get(key);
                if (value != null) {
                    put((String) key, value); // caching
                    break;
                }
            }
        }
        if (value == null) {
            put((String) key, NULL); // cache 'null' value
        }
        return value != NULL ? value : null;
    }

    @Override
    @Nonnull
    public Set<String> keySet() {
        Set<String> keys = new HashSet<>(super.keySet());
        for (ValueMap map : cascade) {
            keys.addAll(map.keySet());
        }
        return keys;
    }

    @Override
    @Nonnull
    public Collection<Object> values() {
        cacheAll();
        return super.values();
    }

    @Override
    @Nonnull
    public Set<Map.Entry<String, Object>> entrySet() {
        cacheAll();
        return super.entrySet();
    }
}
