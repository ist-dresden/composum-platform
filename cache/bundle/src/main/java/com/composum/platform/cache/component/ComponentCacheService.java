package com.composum.platform.cache.component;

import com.composum.platform.cache.service.CacheManager;
import com.composum.platform.cache.service.CacheService;
import com.composum.sling.core.filter.ResourceFilter;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
        resourceFilterAlways = new ResourceFilter.FilterSet(ResourceFilter.FilterSet.Rule.or, config.resourceFilterAlways());
        resourceFilterAnonOnly = new ResourceFilter.FilterSet(ResourceFilter.FilterSet.Rule.or, config.resourceFilterAnonOnly());
        LOG.info("activate.enabled: {} ...", config.enabled());
    }

    @Deactivate
    protected void deactivate() {
        includeCacheService = null;
    }

    @Nullable
    protected CacheService<String> getIncludeCacheService() {
        if (includeCacheService == null && config.enabled()) {
            // it's possible that the cache is not registered at activation of this service, we try it on demand
            includeCacheService = cacheManager.getCache(config.includeCache(), String.class);
            LOG.info("cacheService: {}", includeCacheService);
        }
        return includeCacheService;
    }

    @Override
    @Nonnull
    public Config getConfig() {
        return config;
    }

    /**
     * returns true if the debug feature is enabled by request and by service configuration
     * (or cache feature debug 'on' in the service configuration) and the cache service is
     * available and it is an 'HTML' request
     * <p>
     * This is intentionally enabled when config.enabled() is false but config.debug() is true
     * to allow debugging before switching it on.
     */
    @Override
    public boolean isIncludeCacheEnabled(ServletRequest request, ServletResponse response) {
        return !isComponentCacheDisabled(request) && (config.debug() || getIncludeCacheService() != null) &&
                request instanceof SlingHttpServletRequest && response instanceof SlingHttpServletResponse &&
                "html".equals(((SlingHttpServletRequest) request).getRequestPathInfo().getExtension());
    }

    /**
     * checks the request attribute 'ComponentCache.ATTR_CACHE_DISABLED' (controlled by another filter)
     */
    protected boolean isComponentCacheDisabled(ServletRequest request) {
        Boolean disabled = (Boolean) request.getAttribute(ATTR_CACHE_DISABLED);
        return disabled != null && disabled;
    }

    /**
     * returns the cache rule for the resource to include currently
     */
    @Override
    @Nonnull
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

    /**
     * check resource by the 'always' filter set
     */
    protected boolean acceptedByAlwaysFilter(Resource resource) {
        return resourceFilterAlways.getSet().size() > 0 && resourceFilterAlways.accept(resource);
    }

    /**
     * check resource by the 'anonymous only' filter set
     */
    protected boolean acceptedByAnonOnlyFilter(Resource resource) {
        return resourceFilterAnonOnly.getSet().size() > 0 && resourceFilterAnonOnly.accept(resource);
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    @Nullable
    public String getIncludeCacheContent(@Nonnull Serializable key) {
        return getIncludeCacheService().get(key);
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public void setIncludeCacheContent(@Nonnull Serializable key, @Nullable String content) {
        getIncludeCacheService().put(key, content);
    }

    @Override
    public boolean isDebugRequest(SlingHttpServletRequest request) {
        Boolean isDebug = (Boolean) request.getAttribute(ATTR_IS_DEBUG_REQUEST);
        return isDebug != null && isDebug;
    }
}
