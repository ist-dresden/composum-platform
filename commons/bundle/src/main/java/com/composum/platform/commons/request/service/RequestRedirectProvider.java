package com.composum.platform.commons.request.service;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;

import javax.annotation.Nonnull;

/**
 * an implementation of a request redirect strategy used by the RequestRedirectService to dispatch redirects
 */
public interface RequestRedirectProvider {

    /**
     * @return an identifier for the provider implementation
     */
    String getName();

    /**
     * @return 'true' if the provider is able to handle a redirect for the given request
     */
    boolean canHandle(@Nonnull SlingHttpServletRequest request);

    /**
     * performs the redirect...
     *
     * @return 'true' the response is final for the redirect; 'false' redirect not possible and nothing done
     */
    boolean redirectRequest(@Nonnull SlingHttpServletRequest request,
                            @Nonnull SlingHttpServletResponse response);
}
