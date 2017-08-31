package com.composum.platform.cache.service;

import org.ehcache.Cache;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * the cache manager (collector) service interface
 */
public interface CacheManager {

    /**
     * get a registered cache service; services are collected; returns 'null' if service not yet registered
     */
    @Nullable
    <T extends Serializable> CacheService<T> getCache(@Nonnull String name, @Nonnull Class<T> type);

    /**
     * use (if available) or set up the cache instance
     */
    @Nonnull
    Cache useCache(@Nonnull String name, @Nonnull org.ehcache.config.CacheConfiguration config);

    /**
     * drop the cache instance
     */
    void removeCache(@Nonnull String name);
}
