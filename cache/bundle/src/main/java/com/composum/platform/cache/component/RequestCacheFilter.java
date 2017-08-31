package com.composum.platform.cache.component;

import com.composum.platform.commons.response.DropResponseWrapper;
import com.google.gson.stream.JsonWriter;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
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
import java.util.Arrays;
import java.util.List;

/**
 * the request filter to initialize the include filter debug feature
 */
@Component(
        service = {Filter.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Component Request Cache Filter",
                "sling.filter.scope=REQUEST",
                "service.ranking:Integer=" + 4910
        }
)
public class RequestCacheFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestCacheFilter.class);

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

        if (service.getConfig().debug()) {
            final SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
            final SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;
            final RequestPathInfo pathInfo = slingRequest.getRequestPathInfo();
            final List<String> selectors = Arrays.asList(pathInfo.getSelectors());
            final boolean isDebug = "html".equals(pathInfo.getExtension()) &&
                    selectors.contains("cache") && selectors.contains("debug");
            // debug detection done with the 'isDebug' result for further processing
            request.setAttribute(ComponentCache.ATTR_IS_DEBUG_REQUEST, isDebug);
            if (isDebug) {
                // debug request detected: set up the debug info writer and drop the normal response output
                if (LOG.isInfoEnabled()) {
                    LOG.info("cache.debug...");
                }
                response.setContentType("application/json;charset=UTF-8");
                final JsonWriter writer = new JsonWriter(response.getWriter());
                writer.setLenient(true);
                writer.setIndent("    ");
                writer.setHtmlSafe(true);
                request.setAttribute(ComponentCache.ATTR_DEBUG_WRITER, writer);
                response = new DropResponseWrapper(slingResponse);
            }
        }

        chain.doFilter(request, response);
    }
}