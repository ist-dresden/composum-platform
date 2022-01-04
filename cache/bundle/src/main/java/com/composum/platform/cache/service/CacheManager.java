package com.composum.platform.cache.service;

import org.ehcache.Cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.Serializable;

/**
 * the cache manager (collector) service interface
 */
public interface CacheManager {

    /**
     * get a registered cache service; services are collected; returns 'null' if service not yet registered
     */
    @Nullable
    <T extends Serializable> CacheService<T> getCache(@NotNull String name, @NotNull Class<T> type);

    /**
     * use (if available) or set up the cache instance
     */
    @NotNull
    Cache useCache(@NotNull String name, @NotNull org.ehcache.config.CacheConfiguration config);

    /**
     * drop the cache instance
     */
    void removeCache(@NotNull String name);
}
