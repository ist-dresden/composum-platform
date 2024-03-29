package com.composum.platform.commons.request.service;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;

import org.jetbrains.annotations.NotNull;

/**
 * the dispatcher service to handle request redirects by delegating to the appropriate provider
 */
public interface RequestRedirectService {

    /**
     * performs a redirect if possible...
     *
     * @return 'true' the response is final for the redirect; 'false' redirect not possible and nothing done
     */
    boolean redirectRequest(@NotNull SlingHttpServletRequest request,
                            @NotNull SlingHttpServletResponse response);
}
