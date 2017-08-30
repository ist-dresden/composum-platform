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

	@AttributeDefinition()
	boolean enabled() default true;

	@AttributeDefinition()
	String name();

	@AttributeDefinition()
	String contentType() default "java.lang.String";

	@AttributeDefinition()
	int maxElementsInMemory() default 1000;

	@AttributeDefinition()
	int timeToLiveSeconds() default 600;

	@AttributeDefinition()
	int timeToIdleSeconds() default 300;
}
