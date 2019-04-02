package com.composum.sling.platform.staging;

import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.composum.sling.platform.staging.StagingConstants.FROZEN_PROP_NAMES_TO_REAL_NAMES;
import static com.composum.sling.platform.staging.StagingConstants.REAL_PROPNAMES_TO_FROZEN_NAMES;
import static org.apache.jackrabbit.JcrConstants.*;

/**
 * Emulates the normal {@link ValueMap} from the {@link ValueMap} of a frozen resource.
 */
class StagingResourceValueMap extends ValueMapDecorator {

    /**
     * Creates a new wrapper around a given value map of a frozen node.
     */
    StagingResourceValueMap(ValueMap frozen) {
        super(frozen);
    }

    @Override
    @CheckForNull
    public Object get(Object key) {
        if (REAL_PROPNAMES_TO_FROZEN_NAMES.containsKey(key)) {
            return super.get(REAL_PROPNAMES_TO_FROZEN_NAMES.get(key));
        }
        return super.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        if (FROZEN_PROP_NAMES_TO_REAL_NAMES.containsKey(key)) {
            return false;
        }
        return super.containsKey(key);
    }

    @Override
    @Nonnull
    public Set<String> keySet() {
        final Set<String> keys = super.keySet();
        for (Entry<String, String> entry : FROZEN_PROP_NAMES_TO_REAL_NAMES.entrySet()) {
            if (keys.remove(entry.getKey())) {
                keys.add(entry.getValue());
            }
        }
        return keys;
    }

    @Override
    @Nonnull
    public Set<Entry<String, Object>> entrySet() {
        final Set<Entry<String, Object>> entries = super.entrySet();
        final Set<Entry<String, Object>> result = new HashSet<>();
        for (Entry<String, Object> entry : entries) {
            if (FROZEN_PROP_NAMES_TO_REAL_NAMES.values().contains(entry.getKey())) {
                //nothing
            } else if (!FROZEN_PROP_NAMES_TO_REAL_NAMES.keySet().contains(entry.getKey())) {
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
            } else if (FROZEN_PROP_NAMES_TO_REAL_NAMES.keySet().contains(entry.getKey())) {
                result.add(new PrivateEntry(FROZEN_PROP_NAMES_TO_REAL_NAMES.get(entry.getKey()), entry.getValue()));
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
