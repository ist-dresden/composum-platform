package com.composum.sling.platform.security;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.wrappers.SlingHttpServletRequestWrapper;

public class ForwardedSSLRequestWrapper extends SlingHttpServletRequestWrapper {

    public ForwardedSSLRequestWrapper(SlingHttpServletRequest request) {
        super(request);
    }

    @Override
    public int getServerPort() {
        return 443;
    }
}
