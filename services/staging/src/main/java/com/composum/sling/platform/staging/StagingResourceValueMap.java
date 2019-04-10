package com.composum.sling.platform.staging;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

import static com.composum.sling.platform.staging.StagingConstants.FROZEN_PROP_NAMES_TO_REAL_NAMES;
import static com.composum.sling.platform.staging.StagingConstants.REAL_PROPNAMES_TO_FROZEN_NAMES;
import static org.apache.jackrabbit.JcrConstants.*;

/**
 * Emulates the normal {@link ValueMap} from the {@link ValueMap} of a frozen resource.
 */
class StagingResourceValueMap extends ValueMapDecorator {

    private static final Logger LOG = LoggerFactory.getLogger(StagingResourceValueMap.class);

    /**
     * Creates a new wrapper around a given value map of a frozen node.
     */
    StagingResourceValueMap(ValueMap frozen) {
        super(frozen);
        // safety check that we are either wrapping a frozen nodes properties, or a property resources empty set
        if (!frozen.isEmpty() && frozen.get(JCR_FROZENPRIMARYTYPE) == null)
            throw new IllegalArgumentException("Wrap only valuemaps of frozen nodes, but is " + frozen);
    }

    /**
     * A frozen node always has a jcr:uuid and jcr:frozenUuid. If the original node had no uuid, the frozenUuid contains a /.
     * This checks whether jcr:frozenUuid indicates that there originally wasn't a jcr:uuid.
     */
    protected boolean haveToRemoveUuid() {
        return super.get(JCR_UUID) != null && super.get(JCR_FROZENUUID) != null
                && StringUtils.contains(super.get(JCR_FROZENUUID, String.class), '/');
    }

    @Override
    @CheckForNull
    public Object get(Object name) {
        if (JCR_UUID.equals(name) && haveToRemoveUuid()) return null;
        if (JCR_MIXINTYPES.equals(name)) return cleanupMixinTypes(super.get(JCR_FROZENMIXINTYPES), null);
        String transformedName = REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(name, (String) name);
        return super.get(transformedName);
    }

    @Override
    public <T> T get(String name, Class<T> type) {
        if (JCR_UUID.equals(name) && haveToRemoveUuid()) return null;
        if (JCR_MIXINTYPES.equals(name)) return (T) cleanupMixinTypes(super.get(JCR_FROZENMIXINTYPES), type);
        return super.get(REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(name, name), type);
    }

    @Override
    public <T> T get(String name, T defaultValue) {
        if (JCR_UUID.equals(name) && haveToRemoveUuid()) return defaultValue;
        if (JCR_MIXINTYPES.equals(name))
            return (T) cleanupMixinTypes(super.get(JCR_FROZENMIXINTYPES), defaultValue.getClass());
        return super.get(REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(name, name), defaultValue);
    }

    @Override
    public boolean containsKey(Object key) {
        if (FROZEN_PROP_NAMES_TO_REAL_NAMES.containsKey(key))
            return false;
        if (JCR_UUID.equals(key) && haveToRemoveUuid())
            return false;
        if (JCR_MIXINTYPES.equals(key)) return get(JCR_MIXINTYPES, String[].class) != null;
        return super.containsKey(REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(key, (String) key));
    }

    @Override
    @Nonnull
    public Set<String> keySet() {
        final Set<String> keys = new LinkedHashSet<>(super.keySet());
        for (Entry<String, String> entry : FROZEN_PROP_NAMES_TO_REAL_NAMES.entrySet()) {
            if (keys.remove(entry.getKey())) {
                keys.add(entry.getValue());
            }
        }
        if (haveToRemoveUuid()) keys.remove(JCR_UUID);
        if (get(JCR_MIXINTYPES) == null) keys.remove(JCR_MIXINTYPES); // that's cleaned up and might become null.
        return keys;
    }

    /** Remove mix:versionable since it's attributes do not make sense here. */
    protected Object cleanupMixinTypes(Object rawMixinTypes, @Nonnull Class<?> expectedClass) {
        Object result = rawMixinTypes;
        if (rawMixinTypes instanceof String[]) {
            String[] mixins = (String[]) rawMixinTypes;
            if (mixins != null) {
                mixins = Arrays.asList(mixins).stream()
                        .filter((m) -> !MIX_VERSIONABLE.equals(m))
                        .collect(Collectors.toList())
                        .toArray(new String[0]);
                if (mixins.length == 0) rawMixinTypes = null;
                else {
                    result = mixins;
                }
            }
        } else {
            LOG.warn("Requesting mixins with unsupported type {}", expectedClass.getName());
        }
        return result;
    }

    @Override
    @Nonnull
    public Set<Entry<String, Object>> entrySet() {
        final Set<Entry<String, Object>> entries = super.entrySet();
        final Set<Entry<String, Object>> result = new HashSet<>();
        for (Entry<String, Object> entry : entries) {
            if ((JCR_FROZENUUID.equals(entry.getKey()) || JCR_UUID.equals(entry.getKey())) && haveToRemoveUuid()) {
                // nothing
            } else if (entry.getKey().equals(JCR_FROZENMIXINTYPES)) {
                Object value = cleanupMixinTypes(entry.getValue(), null);
                if (value != null) result.add(new PrivateEntry(JCR_MIXINTYPES, value));
            } else if (REAL_PROPNAMES_TO_FROZEN_NAMES.keySet().contains(entry.getKey())) {
                //nothing
            } else if (!FROZEN_PROP_NAMES_TO_REAL_NAMES.keySet().contains(entry.getKey())) {
                result.add(entry);
            } else if (FROZEN_PROP_NAMES_TO_REAL_NAMES.keySet().contains(entry.getKey())) {
                result.add(new PrivateEntry(FROZEN_PROP_NAMES_TO_REAL_NAMES.get(entry.getKey()), entry.getValue()));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public int size() {
        return keySet().size();
    }

    @Override
    public Collection<Object> values() {
        return Collections.unmodifiableCollection(entrySet().stream().map(Entry::getValue).collect(Collectors.toList()));
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Not modifiable");
    }

    @Override
    public Object put(String key, Object value) {
        throw new UnsupportedOperationException("Not modifiable");
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException("Not modifiable");
    }

    @Override
    public boolean remove(Object key, Object value) {
        throw new UnsupportedOperationException("Not modifiable");
    }

    @Override
    public String toString() {
        return entrySet().toString();
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
