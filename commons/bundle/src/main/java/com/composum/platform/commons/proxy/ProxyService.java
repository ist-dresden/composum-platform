package com.composum.platform.commons.proxy;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * the proxy request service interface
 */
public interface ProxyService {

    /**
     * return the key of the service
     */
    @Nonnull
    String getName();

    /**
     * Handles the proxy request if appropriate (target pattern matches and access allowed)
     *
     * @param request   the proxy request
     * @param response  the response for the answer
     * @param targetUrl the url of the request which is addressing the target
     * @return 'true' if the request is supported by the service, allowed for the user and handle by the service
     */
    boolean doProxy(@Nonnull SlingHttpServletRequest request,
                    @Nonnull SlingHttpServletResponse response,
                    @Nonnull String targetUrl)
            throws IOException;
}
