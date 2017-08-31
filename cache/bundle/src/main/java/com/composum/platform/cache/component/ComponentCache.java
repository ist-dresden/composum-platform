package com.composum.platform.cache.component;

import org.apache.sling.api.SlingHttpServletRequest;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.Serializable;

/**
 * the service interface for the component cache service; used by the IncludeCacheFilter
 */
public interface ComponentCache {

    String ATTR_BASE = ComponentCacheService.class.getName() + "#";

    /**
     * the {Boolean} request attribute which can be set by another filter
     * to prevent from caching (e.g. for authoring requests)
     */
    String ATTR_CACHE_DISABLED = ATTR_BASE + "cacheDisabled";

    String ATTR_IS_EMBEDDING = ATTR_BASE + "isEmbedding";
    String ATTR_IS_DEBUG_REQUEST = ATTR_BASE + "isDebugRequest";
    String ATTR_DEBUG_WRITER = ATTR_BASE + "debugWriter";

    enum CachePolicy {
        noCache,    // caching not enabled for this resource
        always,     // cache this resource always if rendered
        anonOnly,   // cache this resource only if request is not probably personalized
        embedding,  // this resource has to be rendered for the cache content (on cache building)
        embedded    // this resource is embedded in a cached object (used on debug rendering)
    }

    @ObjectClassDefinition(
            name = "Component Cache Configuration"
    )
    @interface Config {

        @AttributeDefinition(
                description = "the general on/off switch for this service"
        )
        boolean enabled() default false;

        @AttributeDefinition(
                description = "eanbles the filter debug feature (selector 'cache.debug') if 'true' (selected)"
        )
        boolean debug() default false;

        @AttributeDefinition(
                description = "the cache service instance key to cache component content during Sling include"
        )
        String includeCache() default "componentIncludeCache";

        @AttributeDefinition(
                description = "the set of ResourceFilter rule strings, e.g. 'ResourceType(and{+'^myapp/components/.*',-'.*/[^/]*page[^/]*$'})'"
                        + " - cache all 'myapp' (whitelist: +'...') components except 'page' components (blacklist: -'...')"
        )
        String[] resourceFilterAlways() default {};

        @AttributeDefinition(
                description = "the set of ResourceFilter rule strings for all components which should be cached only if request is not probably personalized"
        )
        String[] resourceFilterAnonOnly() default {};
    }

    /**
     * makes the configuration accessible for service consumers
     */
    @Nonnull
    Config getConfig();

    /**
     * returns 'true' if the caching is enabled in a current requests context and all preconditions are complied with
     */
    boolean isIncludeCacheEnabled(ServletRequest request, ServletResponse response);

    /**
     * get the cache element referenced by the key if such an element is available in the cache
     */
    @Nullable
    String getIncludeCacheContent(Serializable key);

    /**
     * stores an element in the cache, it the value is 'null' an probably existing cache element is removed
     */
    void setIncludeCacheContent(Serializable key, String content);

    /**
     * returns the caching rule for the requested resource in the current context
     */
    @Nonnull
    CachePolicy getCachePolicy(SlingHttpServletRequest request);

    /**
     * returns 'true' if a cache debugging request is detected (debug enabled + selector 'cache.debug')
     */
    boolean isDebugRequest(SlingHttpServletRequest request);
}