package com.composum.platform.models.simple;

import com.composum.platform.models.annotations.InternationalizationStrategy;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.SlingBean;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * a simple memory driven resource model
 */
public class LoadedModel implements SlingBean {

    protected BeanContext context;
    protected LoadedResource resource;
    protected Locale locale;

    private transient GenericProperties properties;
    private transient List<String> i18nPaths;

    @Override
    public void initialize(BeanContext context, Resource resource) {
        this.context = context;
        this.resource = new LoadedResource(resource);
        locale = context.getLocale();
        if (locale == null) {
            SlingHttpServletRequest request = context.getRequest();
            if (request != null) {
                locale = request.getLocale();
            }
        }
    }

    @Override
    public void initialize(BeanContext context) {
        initialize(context, context.getResource());
    }

    @Override
    @Nonnull
    public String getName() {
        return resource.getName();
    }

    @Override
    @Nonnull
    public String getPath() {
        return resource.getPath();
    }

    @Override
    @Nonnull
    public String getType() {
        return resource.getResourceType();
    }

    /**
     * extension hook
     */
    protected ValueMap getValues(){
        return resource.getValueMap();
    }

    @Nullable
    public <T> T getProperty(@Nonnull String key, Class<T> type) {
        return getValues().get(key, type);
    }

    @Nonnull
    public <T> T getProperty(@Nonnull String key, @Nonnull T defaultValue) {
        return getValues().get(key, defaultValue);
    }

    public String getDate(@Nonnull String... keyChain) {
        Calendar date = null;
        for (String key : keyChain) {
            if ((date = getProperty(key, Calendar.class)) != null) {
                break;
            }
        }
        return date != null ? new SimpleDateFormat("yyyy-MM-dd HH:mm").format(date.getTime()) : "";
    }

    @Nonnull
    public GenericProperties i18n() {
        if (properties == null) {
            properties = new GenericProperties();
        }
        return properties;
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

    @Nonnull
    public Resource getResource() {
        return resource;
    }

    public Locale getLocale() {
        return locale;
    }

    protected List<String> getI18nPaths() {
        if (i18nPaths == null) {
            i18nPaths = InternationalizationStrategy.I18NFOLDER.getI18nPaths(getLocale());
        }
        return i18nPaths;
    }
}
