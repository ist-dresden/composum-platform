package com.composum.platform.commons.proxy;

import org.apache.http.client.protocol.HttpClientContext;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.RepositoryException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component(
        service = {ProxyManagerService.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Proxy Manager Service"
        },
        immediate = true
)
public class ProxyManagerServiceImpl implements ProxyManagerService {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyManagerServiceImpl.class);

    protected final Object lockObject = new Object();

    protected final Map<String, WeakReference<ProxyService>> proxyServices = new LinkedHashMap<>();

    @Nullable
    @Override
    public ProxyService findProxyService(@NotNull String proxyKey) {
        ProxyService proxyService;
        synchronized (lockObject) {
            WeakReference<ProxyService> ref = proxyServices.get(proxyKey);
            proxyService = ref != null ? ref.get() : null;
        }
        return proxyService;
    }

    @NotNull
    @Override
    public List<String> getProxyKeys() {
        synchronized (lockObject) {
            return Collections.unmodifiableList(new ArrayList(proxyServices.keySet()));
        }
    }

    @Override
    public void initHttpContext(@NotNull String proxyKey, @NotNull HttpClientContext context,
                                @Nullable ResourceResolver resolver) throws IllegalArgumentException, RepositoryException {
        ProxyService proxyService = findProxyService(proxyKey);
        if (null == proxyService) {
            throw new IllegalArgumentException("Can't find proxy with the given key.");
        }
        proxyService.initHttpContext(context, resolver);
    }

    @Reference(
            service = ProxyService.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE
    )
    protected void addProxyService(@NotNull ProxyService service) {
        LOG.info("Adding service {} : {}", service.getProxyKey(),
                service.getClass().getName() + "@" + System.identityHashCode(service));
        synchronized (lockObject) {
            ProxyService currentService = findProxyService(service.getProxyKey());
            if (currentService != null) {
                LOG.error("Overwriting existing service {} : {}", service.getProxyKey(),
                        service.getClass().getName() + "@" + System.identityHashCode(service));
            }
            proxyServices.put(service.getProxyKey(), new WeakReference<>(service));
        }
    }

    protected void removeProxyService(@NotNull ProxyService service) {
        LOG.info("Removing service {} : {}", service.getProxyKey(),
                service.getClass().getName() + "@" + System.identityHashCode(service));
        synchronized (lockObject) {
            ProxyService currentService = findProxyService(service.getProxyKey());
            if (currentService == null) { // OSGI malfunction?
                LOG.info("Service was already removed {} : {}", service.getProxyKey(),
                        service.getClass().getName() + "@" + System.identityHashCode(service));
            } else //noinspection ObjectEquality
                if (service != currentService) {
                    LOG.error("Different service registered for key {} : {}", service.getProxyKey(),
                            service.getClass().getName() + "@" + System.identityHashCode(service));
                    // but remove it anyway if there is some kind of wrapping
                }
            proxyServices.remove(service.getProxyKey());
        }
    }

    @Deactivate
    protected void deactivate() {
        synchronized (lockObject) {
            proxyServices.clear();
        }
    }
}
