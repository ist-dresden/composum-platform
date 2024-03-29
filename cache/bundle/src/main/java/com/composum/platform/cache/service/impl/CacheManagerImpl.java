package com.composum.platform.cache.service.impl;

import com.composum.platform.cache.service.CacheManager;
import com.composum.platform.cache.service.CacheService;
import org.ehcache.Cache;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * the default cache manager service implementation (collects all configured cache service instances)
 */
@Component(
        service = {CacheManager.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Cache Services Manager"
        },
        immediate = true
)
public class CacheManagerImpl implements CacheManager {

    private static final Logger LOG = LoggerFactory.getLogger(CacheManagerImpl.class);

    protected org.ehcache.CacheManager ehCacheManager;
    protected Map<String, CacheService> instances = Collections.synchronizedMap(new HashMap<String, CacheService>());

    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public <T extends Serializable> CacheService<T> getCache(@NotNull String name, @NotNull Class<T> type) {
        return instances.get(name);
    }

    @Override
    @NotNull
    public Cache useCache(@NotNull String name, @NotNull CacheConfiguration config) {
        Cache cache = ehCacheManager.getCache(name, config.getKeyType(), config.getValueType());
        if (cache == null) {
            cache = ehCacheManager.createCache(name, config);
        }
        return cache;
    }

    @Override
    public void removeCache(@NotNull String name) {
        ehCacheManager.removeCache(name);
    }

    @Reference(service = CacheService.class, policy = ReferencePolicy.DYNAMIC, cardinality = ReferenceCardinality.MULTIPLE)
    protected void addCacheService(@NotNull final CacheService service) {
        LOG.info("addCacheService: {}", service.getName());
        instances.put(service.getName(), service);
    }

    protected void removeCacheService(@NotNull final CacheService service) {
        LOG.info("removeCacheService: {}", service.getName());
        instances.remove(service.getName());
    }

    @Activate
    public void activate() {
        ehCacheManager = CacheManagerBuilder.newCacheManagerBuilder().build();
        ehCacheManager.init();
        LOG.info("activate.ehCacheManager: {}", ehCacheManager);
    }

    @Deactivate
    public void deactivate() {
        LOG.info("deactivate.ehCacheManager: {}", ehCacheManager);
        if (ehCacheManager != null) {
            ehCacheManager.close();
            ehCacheManager = null;
        }
    }
}
