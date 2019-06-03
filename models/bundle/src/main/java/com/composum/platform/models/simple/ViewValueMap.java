package com.composum.platform.models.simple;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.wrappers.ValueMapDecorator;

import java.util.Map;

public class ViewValueMap extends ValueMapDecorator {

    public ViewValueMap(Map<String, Object> base) {
        super(base);
    }

    /**
     * assuming the Map.get() is used in templates a multi value is joined to a String
     */
    @Override
    public Object get(Object key) {
        Object value = super.get(key);
        return value instanceof Object[] ? StringUtils.join((Object[]) value, ", ") : value;
    }
}
