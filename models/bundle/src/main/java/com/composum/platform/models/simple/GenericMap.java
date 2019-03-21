package com.composum.platform.models.simple;

import com.composum.sling.core.util.PropertyUtil;
import org.apache.sling.api.resource.ValueMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * a caching property map for direct use as model with i18N support
 */
public abstract class GenericMap extends HashMap<String, Object> implements ValueMap {

    public static final String UNDEFINED = "<undefined>";

    protected final List<String> i18nPaths;

    protected GenericMap(final List<String> i18nPaths) {
        this.i18nPaths = i18nPaths != null ? i18nPaths : Collections.singletonList(".");
    }

    @Nullable
    public <T> T get(@Nonnull String key, @Nonnull Class<T> type) {
        //noinspection unchecked
        return (T) get(key);
    }

    @Nonnull
    public <T> T get(@Nonnull String key, @Nonnull T defaultValue) {
        Class<T> type = PropertyUtil.getType(defaultValue);
        T value = get(key, type);
        return value != null ? value : defaultValue;
    }

    /**
     * delegates each 'get' to the localized methods and caches the result
     */
    @Override
    public Object get(Object key) {
        Object value = super.get(key);
        if (value == null) {
            value = getValue((String) key, i18nPaths);
            super.put((String) key, value != null ? value : UNDEFINED);
        }
        return value != UNDEFINED ? value : null;
    }

    protected Object getValue(String key, List<String> pathsToTry) {
        Object value;
        for (String path : pathsToTry) {
            // a local path ('./...') doesn't work properly in the LoadedModel; therefore checked here
            if ((value = getValue(".".equals(path) ? key : path + '/' + key)) != null) {
                return value;
            }
        }
        return null;
    }

    protected abstract Object getValue(String key);
}
