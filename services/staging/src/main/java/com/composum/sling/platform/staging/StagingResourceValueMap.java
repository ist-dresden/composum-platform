package com.composum.sling.platform.staging;

import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.jackrabbit.JcrConstants.*;

class StagingResourceValueMap extends ValueMapDecorator {

    private Map<String, String> typesToMap = new HashMap<>();
    {
        typesToMap.put(JCR_FROZENPRIMARYTYPE, JCR_PRIMARYTYPE);
        typesToMap.put(JCR_FROZENUUID, JCR_UUID);
        typesToMap.put(JCR_FROZENMIXINTYPES, JCR_MIXINTYPES);
    }

    /**
     * Creates a new wrapper around a given map.
     */
    StagingResourceValueMap(ValueMap frozen) {
        super(frozen);
    }

    @Override
    @CheckForNull
    public Object get(Object key) {
        if (JCR_PRIMARYTYPE.equals(key)) {
            return super.get(JCR_FROZENPRIMARYTYPE);
        } else if (JCR_MIXINTYPES.equals(key)) {
            return super.get(JCR_FROZENMIXINTYPES);
        } else if (JCR_UUID.equals(key)) {
            return super.get(JCR_FROZENUUID);
        }
        return super.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        if (JCR_FROZENPRIMARYTYPE.equals(key)) {
            return false;
        } else if (JCR_FROZENMIXINTYPES.equals(key)) {
            return false;
        } else if (JCR_FROZENUUID.equals(key)) {
            return false;
        }
        return super.containsKey(key);
    }

    @Override
    @Nonnull
    public Set<String> keySet() {
        final Set<String> keys = super.keySet();
        if (keys.remove(JCR_FROZENPRIMARYTYPE)) {
            keys.add(JCR_PRIMARYTYPE);
        }
        if (keys.remove(JCR_FROZENMIXINTYPES)) {
            keys.add(JCR_MIXINTYPES);
        }
        if (keys.remove(JCR_FROZENUUID)) {
            keys.add(JCR_UUID);
        }
        return keys;
    }

    @Override
    @Nonnull
    public Set<Entry<String, Object>> entrySet() {
        final Set<Entry<String, Object>> entries = super.entrySet();
        final Set<Entry<String, Object>> result = new HashSet<>(entries.size() - typesToMap.keySet().size());
        for (Entry<String, Object> entry : entries) {
            if (typesToMap.values().contains(entry.getKey())) {
                //nothing
            } else if (!typesToMap.keySet().contains(entry.getKey())) {
                result.add(entry);
            } else if (entry.getKey().equals(JCR_FROZENMIXINTYPES)) {
                final Object value = entry.getValue();
                if (value instanceof String[]) {
                    List<String> ms = new ArrayList<>();
                    for (String mix : (String[]) value) {
                        if (!mix.equals(MIX_VERSIONABLE)) {
                            ms.add(mix);
                        }
                    }
                    if (!ms.isEmpty()) {
                        result.add(new PrivateEntry(JCR_MIXINTYPES, ms));
                    }
                } else {
                    result.add(new PrivateEntry(JCR_MIXINTYPES, entry.getValue()));
                }
            } else if (typesToMap.keySet().contains(entry.getKey())) {
                result.add(new PrivateEntry(typesToMap.get(entry.getKey()), entry.getValue()));
            }
        }
        return result;
    }

    private class PrivateEntry implements Entry<String, Object> {

        private String key;
        private Object value;

        /**
         * Constructs a new entry with the given key and given value.
         *
         * @param key   the key for the entry, may be null
         * @param value the value for the entry, may be null
         */
        PrivateEntry(@Nonnull String key, Object value) {
            this.key = key;
            this.value = value;
        }

        @Override
        @Nonnull
        public String getKey() {
            return key;
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public Object setValue(Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PrivateEntry that = (PrivateEntry) o;

            if (!key.equals(that.key)) return false;
            return value != null ? value.equals(that.value) : that.value == null;

        }

        @Override
        public int hashCode() {
            int result = key.hashCode();
            result = 31 * result + (value != null ? value.hashCode() : 0);
            return result;
        }

        @Override
        @Nonnull
        public String toString() {
            return key + "=" + value;
        }
    }
}
