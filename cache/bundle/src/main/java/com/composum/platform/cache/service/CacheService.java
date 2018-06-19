package com.composum.platform.cache.service;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * the cache service interface
 */
public interface CacheService<T> {

	/**
	 * return the key of the service and of the cache
	 */
	@Nonnull
	String getName();

	/**
	 * returns a cached value
	 */
	@Nullable
	T get(@Nonnull Serializable key);

	/**
	 * sets a cache value; removes a cache entry if value is 'null'
	 */
	void put(@Nonnull Serializable key, @Nullable T value);
}
