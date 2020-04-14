package com.composum.platform.commons.proxy;

import org.apache.http.client.protocol.HttpClientContext;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.util.List;

/**
 * Central access point for all configured {@link ProxyService}s - helper to use HTTP(S) proxies to access remote
 * systems.
 */
public interface ProxyManagerService {

    /** Returns the {@link ProxyService} with the given key as {@link ProxyService#getProxyKey()}. */
    @Nullable
    ProxyService findProxyService(@Nonnull String proxyKey);

    /** Returns a list of the {@link ProxyService#getProxyKey()}s of all configured proxies. */
    @Nonnull
    List<String> getProxyKeys();

    /**
     * Initializes the HttpClientContext with the information about the proxy.
     *
     * @param proxyKey the systemwide unique key for the proxy
     * @param context  the context to be initialized
     * @param resolver a resolver for authentication purposes, if credentials are needed.
     * @throws IllegalArgumentException if there was no proxy with the given proxyKey or there was a problem with it's
     *                                  credential configuration
     */
    void initHttpContext(@Nonnull String proxyKey, @Nonnull HttpClientContext context,
                         @Nullable ResourceResolver resolver) throws IllegalArgumentException, RepositoryException;

}
