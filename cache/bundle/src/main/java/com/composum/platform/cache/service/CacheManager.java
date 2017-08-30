package com.composum.platform.cache.service;

import org.ehcache.Cache;

import java.io.Serializable;

/**
 * the cache manager (collector) service interface
 */
public interface CacheManager {

    <T extends Serializable> CacheService<T> getCache(String name, Class<T> type);

    Cache useCache(String name, org.ehcache.config.CacheConfiguration config);

    void removeCache(String name);
}
