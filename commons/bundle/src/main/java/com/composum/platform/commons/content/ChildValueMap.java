package com.composum.platform.commons.content;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;

import javax.annotation.Nonnull;

public class ChildValueMap extends ValueMapDecorator {

    protected final String path;

    public ChildValueMap(@Nonnull ValueMap target, @Nonnull String path) {
        super(target);
        this.path = StringUtils.isNotBlank(path) ? (path.endsWith("/") ? path : path + "/") : "";
    }

    @Override
    public <T> T get(@Nonnull String name, @Nonnull Class<T> type) {
        return super.get(path + name, type);
    }

    @Override
    @Nonnull
    public <T> T get(@Nonnull String name, @Nonnull T defaultValue) {
        return super.get(path + name, defaultValue);
    }

    @Override
    public boolean containsKey(Object key) {
        return super.containsKey(path + key);
    }

    @Override
    public Object get(Object key) {
        return super.get(path + key);
    }

    @Override
    public Object put(String key, Object value) {
        return super.put(path + key, value);
    }

    @Override
    public Object remove(Object key) {
        return super.remove(path + key);
    }
}
