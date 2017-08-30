package com.composum.platform.cache.component;

import com.composum.platform.commons.response.TextBufferResponseWrapper;
import com.google.gson.stream.JsonWriter;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
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
 * the include filter to use a component cache during Sling include
 */
@Component(
        service = {Filter.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Component Include Cache Filter",
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

        if (service.isIncludeCacheEnabled(request, response)) {
            SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;

            final ComponentCache.Config config = service.getConfig();
            final ComponentCache.CachePolicy cachePolicy = service.getCachePolicy(slingRequest);
            final Resource resource = slingRequest.getResource();
            final String resourcePath = resource.getPath();

            boolean isDebug = service.isDebugRequest(slingRequest);
            if (isDebug) {
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
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("doFilter -- personalized: " + resourcePath);
                            }
                            chain.doFilter(request, response);
                            break;
                        }
                        // continue witch caching...

                    case always:

                        String content = service.getIncludeCacheContent(resourcePath);
                        request.setAttribute(ComponentCache.ATTR_IS_EMBEDDING, content != null
                                ? ComponentCache.CachePolicy.embedded
                                : ComponentCache.CachePolicy.embedding);

                        if (content == null) {

                            if (LOG.isDebugEnabled()) {
                                LOG.debug("doFilter >> fillCache: " + resourcePath);
                            }

                            final SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;
                            final TextBufferResponseWrapper responseWrapper = new TextBufferResponseWrapper(slingResponse);

                            chain.doFilter(request, responseWrapper);
                            content = responseWrapper.toString();
                            service.setIncludeCacheContent(resourcePath, content);

                        } else {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("doFilter << fromCache: " + resourcePath);
                            }
                            if (isDebug) {
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("doFilter ?? debug: " + resourcePath);
                                }
                                chain.doFilter(request, response);
                            }
                        }

                        response.getWriter().write(content);

                        request.removeAttribute(ComponentCache.ATTR_IS_EMBEDDING);
                        break;

                    case embedding:
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("doFilter ++ embedding: " + resourcePath);
                        }
                        chain.doFilter(request, response);
                        break;

                    case embedded:
                        if (isDebug) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("doFilter ++ embedded: " + resourcePath);
                            }
                            chain.doFilter(request, response);
                        }
                        break;

                    default:
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("doFilter -- noCache: " + resourcePath);
                        }
                        chain.doFilter(request, response);
                        break;
                }
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("doFilter .. disabled: " + resourcePath);
                }
                chain.doFilter(request, response);
            }

            if (isDebug) {
                JsonWriter writer = (JsonWriter) slingRequest.getAttribute(ComponentCache.ATTR_DEBUG_WRITER);
                writer.endArray();
                writer.endObject();
            }

        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("doDefaultFilterChain...");
            }
            chain.doFilter(request, response);
        }
    }
}