package com.composum.platform.cache.component;

import org.apache.sling.api.SlingHttpServletRequest;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.Serializable;

/**
 * the service interface for the component cache service
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

        @AttributeDefinition()
        boolean enabled() default false;

        @AttributeDefinition()
        boolean debug() default false;

        @AttributeDefinition()
        String includeCache() default "componentIncludeCache";

        @AttributeDefinition()
        String[] resourceFilterAlways() default {};

        @AttributeDefinition()
        String[] resourceFilterAnonOnly() default {};
    }

    Config getConfig();

    boolean isIncludeCacheEnabled(ServletRequest request, ServletResponse response);

    String getIncludeCacheContent(Serializable key);

    void setIncludeCacheContent(Serializable key, String content);

    CachePolicy getCachePolicy(SlingHttpServletRequest request);

    boolean isDebugRequest(SlingHttpServletRequest request);
}