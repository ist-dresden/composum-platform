package com.composum.platform.cache.service.impl;

import com.composum.platform.cache.service.CacheConfiguration;
import com.composum.platform.cache.service.CacheManager;
import com.composum.platform.cache.service.CacheService;
import org.ehcache.Cache;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.expiry.Duration;
import org.ehcache.expiry.Expirations;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ServiceScope;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * a configurable caching service factory
 */
@Component(service = CacheService.class, scope = ServiceScope.PROTOTYPE)
@Designate(ocd = CacheConfiguration.class, factory = true)
public class CacheServiceImpl<T> implements CacheService<T> {

    private static final Logger LOG = LoggerFactory.getLogger(CacheServiceImpl.class);

    @Reference
    protected CacheManager cacheManager;

    protected CacheConfiguration config;
    protected Cache cache;

    @Activate
    @Modified
    protected void activate(final CacheConfiguration config) {
        this.config = config;
        if (config.enabled()) {
            Class<?> type;
            try {
                type = Class.forName(config.contentType());
            } catch (ClassNotFoundException cnfex) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(cnfex.getMessage(), cnfex);
                }
                type = Object.class;
            }
            org.ehcache.config.CacheConfiguration cacheConfig =
                    CacheConfigurationBuilder.newCacheConfigurationBuilder(Serializable.class, type,
                            ResourcePoolsBuilder.heap(config.maxElementsInMemory()))
                            .withExpiry(Expirations.timeToLiveExpiration(Duration.of(config.timeToLiveSeconds(), TimeUnit.SECONDS)))
                            .withExpiry(Expirations.timeToIdleExpiration(Duration.of(config.timeToIdleSeconds(), TimeUnit.SECONDS)))
                            .build();
            cache = cacheManager.useCache(config.name(), cacheConfig);
        }
        LOG.info("activate: enabled: {} - cache: {}", config.enabled(), cache);
    }

    /**
     * extension hook for subclasses
     */
    protected void activate(final CacheManager cacheManager, final CacheConfiguration config) {
        this.cacheManager = cacheManager;
        activate(config);
    }

    @Deactivate
    protected void deactivate() {
        if (cache != null) {
            cacheManager.removeCache(config.name());
            cache = null;
        }
    }

    @Override
    @NotNull
    public String getName() {
        return config.name();
    }

    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public T get(@NotNull Serializable key) {
        return (T) cache.get(key);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void put(@NotNull Serializable key, @Nullable T value) {
        if (value != null) {
            cache.put(key, value);
        } else {
            cache.remove(key);
        }
    }

    /**
     * cleares the cache, all entries are removed
     */
    @Override
    public void clear() {
        cache.clear();
    }
}
