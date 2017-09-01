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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * a configurable caching service factory
 */
@Component(service = CacheService.class, scope = ServiceScope.PROTOTYPE)
@Designate(ocd = CacheConfiguration.class, factory = true)
public class CacheServiceImpl<T extends Serializable> implements CacheService<T> {

    private static final Logger LOG = LoggerFactory.getLogger(CacheServiceImpl.class);

    @Reference
    protected CacheManager cacheManager;

    protected CacheConfiguration config;
    protected Cache cache;

    @Activate
    @Modified
    public void activate(final CacheConfiguration config) {
        this.config = config;
        if (config.enabled()) {
            try {
                Class<?> type = Class.forName(config.contentType());
                org.ehcache.config.CacheConfiguration cacheConfig =
                        CacheConfigurationBuilder.newCacheConfigurationBuilder(Serializable.class, type,
                                ResourcePoolsBuilder.heap(config.maxElementsInMemory()))
                                .withExpiry(Expirations.timeToLiveExpiration(Duration.of(config.timeToLiveSeconds(), TimeUnit.SECONDS)))
                                .withExpiry(Expirations.timeToIdleExpiration(Duration.of(config.timeToIdleSeconds(), TimeUnit.SECONDS)))
                                .build();
                cache = cacheManager.useCache(config.name(), cacheConfig);
            } catch (ClassNotFoundException ex) {
                LOG.error(ex.getMessage(), ex);
            }
        }
        LOG.info("activate: enabled: {} - cache: {}", config.enabled(), cache);
    }

    @Deactivate
    public void deactivate() {
        if (cache != null) {
            cacheManager.removeCache(config.name());
            cache = null;
        }
    }

    @Override
    @Nonnull
    public String getName() {
        return config.name();
    }

    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public T get(@Nonnull Serializable key) {
        return (T) cache.get(key);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void put(@Nonnull Serializable key, @Nullable T value) {
        if (value != null) {
            cache.put(key, value);
        } else {
            cache.remove(key);
        }
    }
}
