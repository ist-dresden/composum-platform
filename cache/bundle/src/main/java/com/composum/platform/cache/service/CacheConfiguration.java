package com.composum.platform.cache.service;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * the general caching service configuration 'object'
 */
@ObjectClassDefinition(
        name = "Cache Service Configuration"
)
public @interface CacheConfiguration {

    @AttributeDefinition(
            description = "the general on/off switch for this service"
    )
    boolean enabled() default true;

    @AttributeDefinition(
            description = "the name (cache key) of the service"
    )
    String name();

    @AttributeDefinition(
            description = "the type (Java class name) of the cached values"
    )
    String contentType() default "java.lang.String";

    @AttributeDefinition(
            description = "the count maximum of values stored in the cache"
    )
    int maxElementsInMemory() default 1000;

    @AttributeDefinition(
            description = "the validity period maximum in seconds"
    )
    int timeToLiveSeconds() default 1200;

    @AttributeDefinition(
            description = "the validity period after last access in seconds"
    )
    int timeToIdleSeconds() default 600;

    @AttributeDefinition()
    String webconsole_configurationFactory_nameHint() default
            "{name} (enabled: {enabled}, heap: {maxElementsInMemory}, time: {timeToIdleSeconds}-{timeToLiveSeconds})";
}
