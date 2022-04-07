package com.composum.sling.platform.security;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import java.io.IOException;

public interface PlatformAccessFilterAuthPlugin {

    /**
     * initiates the authentication for a requests which needs authentication and authorization
     * @param request the current request (of the filter chain)
     * @param response the current response (of the filter chain)
     * @param chain the filter chain which has triggered this method
     * @return 'true' if the filter chain execution should end
     */
    boolean triggerAuthentication(SlingHttpServletRequest request, SlingHttpServletResponse response,
                                  FilterChain chain)
            throws ServletException, IOException;

    /**
     * examines a request handled by the access filter to check for authentication actions to handle by the plugin
     * @param request the current request (of the filter chain)
     * @param response the current response (of the filter chain)
     * @param chain the filter chain which has triggered this method
     * @return 'true' if the filter chain execution should end
     */
    boolean examineRequest(SlingHttpServletRequest request, SlingHttpServletResponse response,
                           FilterChain chain)
            throws ServletException, IOException;
}
