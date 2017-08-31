package com.composum.platform.cache.component;

import com.composum.platform.cache.service.CacheManager;
import com.composum.platform.cache.service.CacheService;
import com.composum.sling.core.filter.ResourceFilter;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.Serializable;

/**
 * the default component cache service implementation
 */
@Component(
        property = {
                Constants.SERVICE_DESCRIPTION + "=Component Cache"
        }
)
@Designate(ocd = ComponentCacheService.Config.class)
public class ComponentCacheService implements ComponentCache {

    private static final Logger LOG = LoggerFactory.getLogger(ComponentCacheService.class);

    @Reference
    protected CacheManager cacheManager;

    protected Config config;
    protected ResourceFilter.FilterSet resourceFilterAlways;
    protected ResourceFilter.FilterSet resourceFilterAnonOnly;

    protected CacheService<String> includeCacheService;

    @Activate
    @Modified
    protected void activate(final ComponentCacheService.Config config) {
        this.config = config;
        if (config.enabled()) {
            resourceFilterAlways = new ResourceFilter.FilterSet(ResourceFilter.FilterSet.Rule.or, config.resourceFilterAlways());
            resourceFilterAnonOnly = new ResourceFilter.FilterSet(ResourceFilter.FilterSet.Rule.or, config.resourceFilterAnonOnly());
            getIncludeCacheService();
        }
        LOG.info("activate.enabled: {} ...", config.enabled());
    }

    protected CacheService<String> getIncludeCacheService() {
        if (includeCacheService == null) {
            includeCacheService = cacheManager.getCache(config.includeCache(), String.class);
            LOG.info("cacheService: {}", includeCacheService);
        }
        return includeCacheService;
    }

    @Override
    public Config getConfig() {
        return config;
    }

    @Override
    public boolean isIncludeCacheEnabled(ServletRequest request, ServletResponse response) {
        return !isComponentCacheDisabled(request) && getIncludeCacheService() != null &&
                request instanceof SlingHttpServletRequest && response instanceof SlingHttpServletResponse &&
                "html".equals(((SlingHttpServletRequest) request).getRequestPathInfo().getExtension());
    }

    protected boolean isComponentCacheDisabled(ServletRequest request) {
        Boolean disabled = (Boolean) request.getAttribute(ATTR_CACHE_DISABLED);
        return disabled != null && disabled;
    }

    @Override
    public CachePolicy getCachePolicy(SlingHttpServletRequest request) {
        CachePolicy embedState;
        if ((embedState = (CachePolicy) request.getAttribute(ATTR_IS_EMBEDDING)) != null) {
            return embedState;
        }
        final Resource resource = request.getResource();
        if (acceptedByAlwaysFilter(resource)) {
            return CachePolicy.always;
        } else if (acceptedByAnonOnlyFilter(resource)) {
            return CachePolicy.anonOnly;
        }
        return CachePolicy.noCache;
    }

    protected boolean acceptedByAlwaysFilter(Resource resource) {
        return resourceFilterAlways.getSet().size() > 0 && resourceFilterAlways.accept(resource);
    }

    protected boolean acceptedByAnonOnlyFilter(Resource resource) {
        return resourceFilterAnonOnly.getSet().size() > 0 && resourceFilterAnonOnly.accept(resource);
    }

    @Override
    public String getIncludeCacheContent(Serializable key) {
        return getIncludeCacheService().get(key);
    }

    @Override
    public void setIncludeCacheContent(Serializable key, String content) {
        getIncludeCacheService().put(key, content);
    }

    @Override
    public boolean isDebugRequest(SlingHttpServletRequest request) {
        Boolean isDebug = (Boolean) request.getAttribute(ATTR_IS_DEBUG_REQUEST);
        return isDebug != null && isDebug;
    }
}