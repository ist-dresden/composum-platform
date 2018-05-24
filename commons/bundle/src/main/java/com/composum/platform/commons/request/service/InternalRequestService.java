package com.composum.platform.commons.request.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.request.RequestProgressTracker;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.servlethelpers.MockRequestPathInfo;
import org.apache.sling.servlethelpers.MockSlingHttpServletRequest;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.security.Principal;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * simulates a request to a resource to provide internal content rendering
 */
public interface InternalRequestService {

    String RA_IS_INTERNAL_REQUEST = "composum.platform.isInternalRequest";

    /** the URL pattern to check the URL and separate the path info and query */
    Pattern URL_PATTERN = Pattern.compile("^((https?)://([^/:]+)(:[0-9]+)?)?(/[^?]*)(\\?(.*))?$");

    /** the URI pattern to separate path, selectors, extension and suffix */
    Pattern URI_PATTERN = Pattern.compile("^((/[^.]*)(\\.(.+))?\\.([^.]+))(/.*)?$");

    /**
     * the requests PathInfo simulation mock
     */
    class PathInfo extends MockRequestPathInfo {

        protected ResourceResolver resolver;

        protected String scheme;
        protected String host;
        protected int port;
        protected String query;

        protected String requestUrl;
        protected String requestUri;
        protected String pathInfo;
        protected Resource resource;

        public PathInfo(ResourceResolver resolver, String url) {
            this(resolver);
            setUrl(url);
        }

        public PathInfo(ResourceResolver resolver) {
            this.resolver = resolver;
        }

        /**
         * defines the 'URL' for the internal request; the URL is deconstructed into the path info elements
         * the path info part is used to find the referenced resource and determine the selectors and the suffix
         */
        public void setUrl(String url) {
            Matcher matcher = URL_PATTERN.matcher(url);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("not a valid URL: '" + url + "'");
            }
            this.requestUrl = url;
            if (StringUtils.isNotBlank(matcher.group(1))) {
                scheme = matcher.group(2);
                host = matcher.group(3);
                port = Integer.parseInt(matcher.group(4));
            } else {
                scheme = null;
                host = "localhost";
                port = 0;
            }
            pathInfo = matcher.group(5);
            query = matcher.group(7);
            findResource("", pathInfo);
        }

        protected void findResource(String basePath, String path) {
            Matcher matcher = URI_PATTERN.matcher(path);
            if (!matcher.matches()) {
                if ("".equals(basePath)) {
                    throw new IllegalArgumentException("not a valid URI: '" + pathInfo + "'");
                }
                // return with the current state (from the last try)
                return;
            }
            // check for an existing resource
            String resPath = basePath + emptyOrFilled(matcher.group(2));
            Resource res = resolver.getResource(resPath);
            if (res == null) {
                // the resources name contains probably the extension (asset file)
                String ext = emptyOrFilled(matcher.group(5));
                res = (resolver.getResource(resPath + "." + ext));
                if (res != null) {
                    resPath += "." + ext;
                }
            }
            if (res != null || "".equals(basePath)) {
                // use the current deconstruction
                requestUri = emptyOrFilled(matcher.group(1));
                setSelectorString(emptyOrFilled(matcher.group(4)));
                setExtension(emptyOrFilled(matcher.group(5)));
                setSuffix(emptyOrFilled(matcher.group(6)));
                setResourcePath(resPath);
                resource = res;
            }
            // try to find a better match...
            findResource(matcher.group(1), emptyOrFilled(matcher.group(6)));
        }

        protected String emptyOrFilled(String value) {
            return value != null ? value : "";
        }

        @Override
        public Resource getSuffixResource() {
            return resolver.getResource(getSuffix());
        }

        public Resource getResource() {
            return resource;
        }

        // Request

        public String getPathInfo() {
            return pathInfo;
        }

        public String getRequestUri() {
            return requestUri;
        }

        public String getRequestUrl() {
            return requestUrl;
        }
    }

    class SlingRequest extends MockSlingHttpServletRequest {

        protected final SlingHttpServletRequest contextRequest;
        protected final PathInfo internalPathInfo;

        public SlingRequest(SlingHttpServletRequest contextRequest,
                            PathInfo pathInfo) {
            super(contextRequest.getResourceResolver());
            this.contextRequest = contextRequest;
            internalPathInfo = pathInfo;
        }

        protected MockRequestPathInfo newMockRequestPathInfo() {
            return getInternalPathInfo();
        }

        public PathInfo getInternalPathInfo() {
            return internalPathInfo;
        }

        @Override
        public String getPathInfo() {
            return getInternalPathInfo().getPathInfo();
        }

        @Override
        public String getPathTranslated() {
            return getInternalPathInfo().getResourcePath();
        }

        @Override
        public String getRequestURI() {
            return getInternalPathInfo().getRequestUri();
        }

        @Override
        public StringBuffer getRequestURL() {
            return new StringBuffer(getInternalPathInfo().getRequestUrl());
        }

        // wrapped context request

        @Nonnull
        @Override
        public RequestProgressTracker getRequestProgressTracker() {
            return contextRequest.getRequestProgressTracker();
        }

        @Override
        public String getAuthType() {
            return contextRequest.getAuthType();
        }

        @Override
        public String getContextPath() {
            return "";
        }

        @Override
        public String getServletPath() {
            return "";
        }

        @Override
        public String getRequestedSessionId() {
            return contextRequest.getRequestedSessionId();
        }

        @Override
        public Principal getUserPrincipal() {
            return contextRequest.getUserPrincipal();
        }

        @Override
        public boolean isRequestedSessionIdFromCookie() {
            return contextRequest.isRequestedSessionIdFromCookie();
        }

        @Override
        public boolean isRequestedSessionIdFromURL() {
            return contextRequest.isRequestedSessionIdFromURL();
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean isRequestedSessionIdFromUrl() {
            return contextRequest.isRequestedSessionIdFromUrl();
        }

        @Override
        public boolean isRequestedSessionIdValid() {
            return contextRequest.isRequestedSessionIdValid();
        }

        @Override
        public boolean isUserInRole(String role) {
            return contextRequest.isUserInRole(role);
        }

        @Override
        public String getLocalAddr() {
            return "127.0.0.1";
        }

        @Override
        public String getLocalName() {
            return "localhost";
        }

        @Override
        public int getLocalPort() {
            return contextRequest.getLocalPort();
        }

        @Override
        public Enumeration<Locale> getLocales() {
            return contextRequest.getLocales();
        }
    }

    class SlingResponse extends MockSlingHttpServletResponse {
    }

    /**
     * returns the result of the internal request as string
     *
     * @param contextRequest the 'original' rendering request
     * @param pathInfo       the prepared path info object for the internal request
     */
    @Nonnull
    String getString(@Nonnull SlingHttpServletRequest contextRequest, @Nonnull PathInfo pathInfo)
            throws ServletException, IOException;

    /**
     * returns the result of the internal request as binary data
     *
     * @param contextRequest the 'original' rendering request
     * @param pathInfo       the prepared path info object for the internal request
     */
    @Nonnull
    byte[] getBytes(@Nonnull SlingHttpServletRequest contextRequest, @Nonnull PathInfo pathInfo)
            throws ServletException, IOException;
}
