package com.composum.platform.cache.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.Serializable;

/**
 * the cache service interface
 */
public interface CacheService<T> {

	/**
	 * return the key of the service and of the cache
	 */
	@NotNull
	String getName();

	/**
	 * returns a cached value
	 */
	@Nullable
	T get(@NotNull Serializable key);

	/**
	 * sets a cache value; removes a cache entry if value is 'null'
	 */
	void put(@NotNull Serializable key, @Nullable T value);
}
