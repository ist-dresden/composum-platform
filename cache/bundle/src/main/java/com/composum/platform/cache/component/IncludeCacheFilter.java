package com.composum.platform.cache.component;

import com.composum.platform.commons.response.TextBufferResponseWrapper;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;

/**
 * the include filter to use a {@link ComponentCache} during Sling include
 * <p>
 * If the caching is enabled by the cache service configuration for the resource to include
 * the rendering result is delivered from the cache if available in the cache
 * otherwise the rendering result of the resource is built in a buffer,
 * stored in the cache for further requests and delivered finally.
 * </p>
 */
@Component(
        service = {Filter.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Component Cache Include Filter",
                "sling.filter.scope=INCLUDE",
                "service.ranking:Integer=" + 4900
        }
)
public class IncludeCacheFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(IncludeCacheFilter.class);

    @Reference
    protected ComponentCache service;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // ignore
    }

    @Override
    public void destroy() {
    }

    public final void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (service != null && service.isIncludeCacheEnabled(request, response)) {
            SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;

            final ComponentCache.Config config = service.getConfig();
            final ComponentCache.CachePolicy cachePolicy = service.getCachePolicy(slingRequest);
            final Resource resource = slingRequest.getResource();
            final String resourcePath = resource.getPath();

            boolean isDebug = service.isDebugRequest(slingRequest);
            if (isDebug) {
                // open debug output for the current resource and write the debug info
                JsonWriter writer = (JsonWriter) slingRequest.getAttribute(ComponentCache.ATTR_DEBUG_WRITER);
                writer.beginObject();
                writer.name("path").value(resource.getPath());
                writer.name("type").value(resource.getResourceType());
                writer.name("cache").value(cachePolicy.name());
                writer.name("includes").beginArray();
            }

            if (config.enabled()) {

                switch (cachePolicy) {

                    case anonOnly:
                        ResourceResolver resolver = slingRequest.getResourceResolver();
                        if (!"anonymous".equals(slingRequest.getUserPrincipal().getName())) {
                            // don't cache if a real user is logged in
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("doFilter -- personalized: " + resourcePath);
                            }
                            chain.doFilter(request, response);
                            break;
                        }
                        // fall through: continue witch caching...

                    case always:
                        final String cacheKey = buildCacheKey(slingRequest, resourcePath);

                        String content = service.getIncludeCacheContent(cacheKey);
                        // set the request flag that all resources included in the current resource are cached implicit
                        request.setAttribute(ComponentCache.ATTR_IS_EMBEDDING, content != null
                                ? ComponentCache.CachePolicy.embedded       // always cached or
                                : ComponentCache.CachePolicy.embedding);    // stored in the content buffer

                        if (content == null) {

                            if (LOG.isDebugEnabled()) {
                                LOG.debug("doFilter >> fillCache: " + resourcePath);
                            }

                            final SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;
                            final TextBufferResponseWrapper responseWrapper = new TextBufferResponseWrapper(slingResponse);

                            // render it into the buffer and cache it...
                            chain.doFilter(request, responseWrapper);
                            content = responseWrapper.toString();

                            service.setIncludeCacheContent(cacheKey, content);

                        } else {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("doFilter << fromCache: " + resourcePath);
                            }
                            if (isDebug) {
                                // always in cache but trace it in case of a debug request
                                chain.doFilter(request, response);
                            }
                        }

                        response.getWriter().write(content);

                        // remove the flag for the implicit caching after cache object is built
                        request.removeAttribute(ComponentCache.ATTR_IS_EMBEDDING);
                        break;

                    case embedding:
                        // the cache object has to be built and this resource is included
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("doFilter ++ embedding: " + resourcePath);
                        }
                        chain.doFilter(request, response);
                        break;

                    case embedded:
                        if (isDebug) {
                            // always in cache but trace it in case of a debug request
                            chain.doFilter(request, response);
                        }
                        break;

                    default:
                        // no caching
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("doFilter -- noCache: " + resourcePath);
                        }
                        chain.doFilter(request, response);
                        break;
                }
            } else {
                // caching filter disabled but probably a cache debug request
                if (LOG.isDebugEnabled()) {
                    LOG.debug("doFilter .. disabled: " + resourcePath);
                }
                chain.doFilter(request, response);
            }

            if (isDebug) {
                // close debug output for the current resource
                JsonWriter writer = (JsonWriter) slingRequest.getAttribute(ComponentCache.ATTR_DEBUG_WRITER);
                writer.endArray();
                writer.endObject();
            }

        } else {
            // caching feature disabled for this request
            if (LOG.isDebugEnabled()) {
                LOG.debug("doDefaultFilterChain...");
            }
            chain.doFilter(request, response);
        }
    }

    protected String buildCacheKey(SlingHttpServletRequest request, String resourcePath) {
        StringBuilder cacheKeyBuilder = new StringBuilder(resourcePath);

        // build the cache key using relevant request modifiers
        RequestPathInfo pathInfo = request.getRequestPathInfo();
        String value;
        if (StringUtils.isNotBlank(value = pathInfo.getSelectorString())) {
            cacheKeyBuilder.append('@').append(value);
        }
        if (StringUtils.isNotBlank(value = pathInfo.getSuffix())) {
            cacheKeyBuilder.append('#').append(value);
        }

        return cacheKeyBuilder.toString();
    }
}
