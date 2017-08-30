package com.composum.platform.cache.service;

import java.io.Serializable;

/**
 * the cache service interface
 */
public interface CacheService<T extends Serializable> {

	String getName();

	T get(Serializable key);

	void put(Serializable key, T value);
}
