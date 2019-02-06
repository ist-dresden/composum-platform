package com.composum.platform.models.simple;

import com.composum.platform.models.annotations.InternationalizationStrategy;
import com.composum.sling.core.AbstractServletBean;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * a simple model for property access with I18N support as used in 'Pages'
 */
public class SimpleModel extends AbstractServletBean {

    private transient Map<String, Object> propertiesMap;
    private transient Map<String, Object> inheritedMap;
    private transient List<String> i18nPaths;
    private transient Locale locale;

    /**
     * the generic map for direct use in templates
     */
    public Map<String, Object> getProperties() {
        if (propertiesMap == null) {
            propertiesMap = new GenericProperties();
        }
        return propertiesMap;
    }

    /**
     * the generic map for direct use in templates
     */
    public Map<String, Object> getInherited() {
        if (inheritedMap == null) {
            inheritedMap = new GenericInherited();
        }
        return inheritedMap;
    }

    protected List<String> getI18nPaths() {
        if (i18nPaths == null) {
            i18nPaths = InternationalizationStrategy.I18NFOLDER.getI18nPaths(getLocale());
        }
        return i18nPaths;
    }

    public Locale getLocale() {
        if (locale == null) {
            locale = getRequest().getLocale();
        }
        return locale;
    }

    public class GenericProperties extends GenericMap {

        public GenericProperties() {
            super(getI18nPaths());
        }

        @Override
        public Object getValue(String key) {
            return getProperty(key, Object.class);
        }
    }

    public class GenericInherited extends GenericMap {

        public GenericInherited() {
            super(getI18nPaths());
        }

        @Override
        public Object getValue(String key) {
            return getInherited(key, Object.class);
        }
    }
}
