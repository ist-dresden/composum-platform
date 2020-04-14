package com.composum.sling.platform.staging.impl;

import com.composum.sling.platform.staging.StagingConstants;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

import static com.composum.sling.platform.staging.StagingConstants.FROZEN_PROP_NAMES_TO_REAL_NAMES;
import static com.composum.sling.platform.staging.StagingConstants.PROP_REPLICATED_VERSION;
import static com.composum.sling.platform.staging.StagingConstants.REAL_PROPNAMES_TO_FROZEN_NAMES;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENMIXINTYPES;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENPRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_FROZENUUID;
import static org.apache.jackrabbit.JcrConstants.JCR_MIXINTYPES;
import static org.apache.jackrabbit.JcrConstants.JCR_UUID;

/**
 * Emulates the normal {@link ValueMap} from the {@link ValueMap} of a frozen resource.
 */
public class StagingResourceValueMap extends ValueMapDecorator {

    private static final Logger LOG = LoggerFactory.getLogger(StagingResourceValueMap.class);

    private final String versionUuid;

    /**
     * Creates a new wrapper around a given value map of a frozen node.
     */
    StagingResourceValueMap(Resource frozenResource) {
        this(frozenResource.getValueMap(), versionUuid(frozenResource));
    }

    protected static String versionUuid(Resource frozenResource) {
        String versionUuid = null;
        if (StagingUtils.isStoredVersionTopNode(frozenResource)) {
            versionUuid = frozenResource.getParent().getValueMap().get(JCR_UUID, String.class);
            if (StringUtils.isBlank(versionUuid)) {
                throw new IllegalArgumentException("Bug: Not a version top node: " + frozenResource.getPath());
            }
        }
        return versionUuid;
    }

    protected StagingResourceValueMap(Map<String, Object> frozen, String versionUuid) {
        super(frozen);
        // safety check that we are either wrapping a frozen nodes properties, or a property resources empty set
        if (!frozen.isEmpty() && frozen.get(JCR_FROZENPRIMARYTYPE) == null) {
            throw new IllegalArgumentException("Wrap only valuemaps of frozen nodes, but is " + frozen);
        }
        this.versionUuid = versionUuid;
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
        if (JCR_UUID.equals(name) && haveToRemoveUuid()) { return null; }
        if (PROP_REPLICATED_VERSION.equals(name)) { return versionUuid; }
        if (JCR_MIXINTYPES.equals(name) && versionUuid != null) {
            String[] mixins = super.get(JCR_FROZENMIXINTYPES, new String[0]);
            List<String> mixinList = new ArrayList<>(Arrays.asList(mixins));
            mixinList.add(StagingConstants.TYPE_MIX_REPLICATEDVERSIONABLE);
            return mixinList.toArray(new String[0]);
        }
        String transformedName = REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(name, (String) name);
        return super.get(transformedName);
    }

    @Override
    public <T> T get(String name, Class<T> type) {
        if (JCR_UUID.equals(name) && haveToRemoveUuid()) { return null; }
        if (PROP_REPLICATED_VERSION.equals(name) || JCR_MIXINTYPES.equals(name)) {
            // type casting mechanism is inaccessible from here. :-(
            return type.cast(get(name));
        }
        return super.get(REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(name, name), type);
    }

    @SuppressWarnings("unchecked")
    @Nonnull
    @Override
    public <T> T get(String name, T defaultValue) {
        if (JCR_UUID.equals(name) && haveToRemoveUuid()) { return defaultValue; }
        if (PROP_REPLICATED_VERSION.equals(name) || (versionUuid != null && JCR_MIXINTYPES.equals(name))) {
            // type casting mechanism is inaccessible from here. :-(
            return (T) defaultValue.getClass().cast(get(name));
        }
        return super.get(REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(name, name), defaultValue);
    }

    @Override
    public boolean containsKey(Object name) {
        if (FROZEN_PROP_NAMES_TO_REAL_NAMES.containsKey(name)) { return false; }
        if (JCR_UUID.equals(name) && haveToRemoveUuid()) { return false; }
        if (JCR_MIXINTYPES.equals(name)) { return get(JCR_MIXINTYPES) != null; }
        if (PROP_REPLICATED_VERSION.equals(name)) { return versionUuid != null; }
        return super.containsKey(REAL_PROPNAMES_TO_FROZEN_NAMES.getOrDefault(name, (String) name));
    }

    @Override
    @Nonnull
    public Set<String> keySet() {
        final Set<String> keys = new LinkedHashSet<>(super.keySet());
        for (Entry<String, String> entry : FROZEN_PROP_NAMES_TO_REAL_NAMES.entrySet()) {
            if (keys.remove(entry.getKey())) { keys.add(entry.getValue()); }
        }
        if (haveToRemoveUuid()) { keys.remove(JCR_UUID); }
        if (versionUuid != null) { keys.add(PROP_REPLICATED_VERSION); }
        if (get(JCR_MIXINTYPES) == null) { // use get since that can be computed
            keys.remove(JCR_MIXINTYPES);
        }
        return keys;
    }

    @Override
    @Nonnull
    public Set<Entry<String, Object>> entrySet() {
        final Set<Entry<String, Object>> entries = super.entrySet();
        final Set<Entry<String, Object>> result = new TreeSet<>(Entry.comparingByKey());
        for (Entry<String, Object> entry : entries) {
            if ((JCR_FROZENUUID.equals(entry.getKey())
                    || JCR_UUID.equals(entry.getKey())) && haveToRemoveUuid()
                    || REAL_PROPNAMES_TO_FROZEN_NAMES.containsKey(entry.getKey())) {
                // do not output this
            } else if (FROZEN_PROP_NAMES_TO_REAL_NAMES.containsKey(entry.getKey())) {
                result.add(new PrivateEntry(FROZEN_PROP_NAMES_TO_REAL_NAMES.get(entry.getKey()), entry.getValue()));
            } else {
                result.add(entry);
            }
        }
        if (versionUuid != null) {
            result.add(new PrivateEntry(PROP_REPLICATED_VERSION, versionUuid));
            String[] mixinArray = get(JCR_MIXINTYPES, String[].class);
            // get inserted the missing StagingConstants.TYPE_MIX_REPLICATEDVERSIONABLE ; insert into entry set:
            mixinArray = mixinArray != null ? mixinArray : new String[0];
            result.removeIf((e) -> JCR_MIXINTYPES.equals(e.getKey()));
            result.add(new PrivateEntry(JCR_MIXINTYPES, mixinArray));
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public int size() {
        return keySet().size();
    }

    @Nonnull
    @Override
    public Collection<Object> values() {
        //noinspection SimplifyStreamApiCallChains - we deliberately use entrySet()
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

    private static class PrivateEntry implements Entry<String, Object> {

        final private String key;
        final private Object value;

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
            if (this == o) { return true; }
            if (o == null || getClass() != o.getClass()) { return false; }

            PrivateEntry that = (PrivateEntry) o;

            if (!key.equals(that.key)) { return false; }
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
