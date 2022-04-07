package com.composum.platform.commons.request.service.impl;

import com.composum.platform.commons.request.AccessMode;
import com.composum.platform.commons.request.service.PlatformRequestLogger;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component(
        service = {Filter.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Request Tracking Filter",
                "sling.filter.scope=REQUEST"
        },
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        immediate = true
)
@Designate(ocd = PlatformRequestLogFilter.Config.class)
public class PlatformRequestLogFilter implements Filter {

    @ObjectClassDefinition(name = "Composum Request Tracking Filter"
    )
    @interface Config {

        @AttributeDefinition(name = "enabled",
                description = "The on/off switch for the logging filter")
        boolean enabled() default true;

        @AttributeDefinition(name = "service ranking",
                description = "The ranking to cascade the various loggers.")
        int service_ranking() default 1500;
    }

    protected PlatformRequestLogFilter.Config config;

    @Activate
    protected void activate(final PlatformRequestLogFilter.Config config) {
        this.config = config;
    }

    private static final Logger LOG = LoggerFactory.getLogger(PlatformRequestLogFilter.class);

    protected Map<String, PlatformRequestLogger> loggers = Collections.synchronizedMap(new LinkedHashMap<>());

    @Reference(service = PlatformRequestLogger.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE)
    protected void bindPlatformRequestLogger(@NotNull final PlatformRequestLogger service) {
        LOG.info("bindPlatformRequestLogger({}:{})", service.getName(), service);
        PlatformRequestLogger unbind = loggers.put(service.getName(), service);
        if (unbind != null) {
            LOG.warn("bindPlatformRequestLogger({}:{}) has replaced '{}'", service.getName(), service, unbind);
        }
    }

    protected void unbindPlatformRequestLogger(@NotNull final PlatformRequestLogger service) {
        LOG.info("unbindPlatformRequestLogger({}:{})", service.getName(), service);
        loggers.remove(service.getName());
    }

    @Nullable
    public PlatformRequestLogger getLogger(@Nullable final AccessMode accessMode,
                                           @NotNull final SlingHttpServletRequest request,
                                           @NotNull final SlingHttpServletResponse response) {
        for (PlatformRequestLogger logger : loggers.values()) {
            if (logger.canHandle(accessMode, request, response)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("logging handled by '{}' ({})", logger.getName(), request.getRequestURL());
                }
                return logger;
            }
        }
        return null;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        if (config.enabled()) {
            PlatformRequestLogger.data(servletRequest); // store the request start time
            chain.doFilter(servletRequest, servletResponse);
            if (servletRequest instanceof SlingHttpServletRequest) {
                SlingHttpServletRequest request = (SlingHttpServletRequest) servletRequest;
                SlingHttpServletResponse response = (SlingHttpServletResponse) servletResponse;
                AccessMode accessMode = AccessMode.requestMode(request);
                PlatformRequestLogger logger = getLogger(accessMode, request, response);
                if (logger != null) {
                    logger.logRequest(accessMode, request, response);
                } else {
                    LOG.warn("no logger available for '{}':'{}'", accessMode, request.getRequestURI());
                }
            }
            return;
        }
        chain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void destroy() {

    }
}
