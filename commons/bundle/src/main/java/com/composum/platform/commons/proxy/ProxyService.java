package com.composum.platform.commons.proxy;

import org.apache.http.client.protocol.HttpClientContext;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;

/** Encapsulates information about a proxy. */
public interface ProxyService {

    /** The unique key for the proxy service = the proxy we access. */
    @Nonnull
    String getProxyKey();

    /** Returns true if enabled. */
    boolean isEnabled();

    /** A human-readable name for the proxy. */
    @Nonnull
    String getTitle();

    /** An optional human-readable description of the proxy. */
    @Nullable
    String getDescription();

    /**
     * Initializes the HttpClientContext with the information about the proxy.
     *
     * @param context  the context to be initialized
     * @param resolver a resolver for authentication purposes, if credentials are needed.
     * @throws IllegalArgumentException if there was a problem with the credentials
     */
    void initHttpContext(@Nonnull HttpClientContext context, @Nullable ResourceResolver resolver) throws IllegalArgumentException, RepositoryException;

}
