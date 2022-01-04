package com.composum.platform.commons.proxy;

import org.apache.http.client.protocol.HttpClientContext;
import org.apache.sling.api.resource.ResourceResolver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.jcr.RepositoryException;

/** Encapsulates information about a proxy. */
public interface ProxyService {

    /** The unique key for the proxy service = the proxy we access. */
    @NotNull
    String getProxyKey();

    /** Returns true if enabled. */
    boolean isEnabled();

    /** A human-readable name for the proxy. */
    @NotNull
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
    void initHttpContext(@NotNull HttpClientContext context, @Nullable ResourceResolver resolver) throws IllegalArgumentException, RepositoryException;

}
