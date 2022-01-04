package com.composum.platform.commons.request.wrapper;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.wrappers.SlingHttpServletRequestWrapper;

import org.jetbrains.annotations.NotNull;

/**
 * a wrapper to delegate any request of any type (any extension) to an error page HTML request
 */
public class ErrorpageRequestWrapper extends SlingHttpServletRequestWrapper {

    /**
     * the path info is using the current request path info dynamically and changing the extension
     */
    protected class PathInfo implements RequestPathInfo {

        /**
         * @return the current info object (is changing during request processing)
         */
        protected RequestPathInfo wrapped() {
            return ((SlingHttpServletRequest) ErrorpageRequestWrapper.this.getRequest()).getRequestPathInfo();
        }

        @NotNull
        @Override
        public String getResourcePath() {
            return wrapped().getResourcePath();
        }

        /**
         * @return the error page extension (HTML) instead of the extension requested originally
         */
        @Override
        public String getExtension() {
            return "html";
        }

        @Override
        public String getSelectorString() {
            return wrapped().getSelectorString();
        }

        @NotNull
        @Override
        public String[] getSelectors() {
            return wrapped().getSelectors();
        }

        @Override
        public String getSuffix() {
            return wrapped().getSuffix();
        }

        @Override
        public Resource getSuffixResource() {
            return wrapped().getSuffixResource();
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder(getResourcePath());
            String selectorString = getSelectorString();
            if (StringUtils.isNotBlank(selectorString)) {
                result.append('.').append(selectorString);
            }
            result.append('.').append(getExtension());
            String suffix = getSuffix();
            if (StringUtils.isNotBlank(suffix)) {
                result.append(suffix);
            }
            return result.toString();
        }
    }

    protected final PathInfo pathInfo;

    public ErrorpageRequestWrapper(SlingHttpServletRequest wrappedRequest) {
        super(wrappedRequest);
        pathInfo = new PathInfo();
    }

    /**
     * @return 'GET' as the method to deliver an error page
     */
    @Override
    @NotNull
    public String getMethod() {
        return HttpConstants.METHOD_GET;
    }

    /**
     * @return the extension adjusting request path info object
     */
    @Override
    @NotNull
    public RequestPathInfo getRequestPathInfo() {
        return pathInfo;
    }
}
