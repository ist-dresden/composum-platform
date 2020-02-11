package com.composum.platform.commons.proxy;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.ServletResolverConstants;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.servlet.Servlet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Component(service = Servlet.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum HTTP Proxy Servlet",
                ServletResolverConstants.SLING_SERVLET_PATHS + "=/bin/cpm/proxy",
                ServletResolverConstants.SLING_SERVLET_EXTENSIONS + "=fwd",
                ServletResolverConstants.SLING_SERVLET_METHODS + "=" + HttpConstants.METHOD_GET
        })
public class ProxyServlet extends SlingSafeMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyServlet.class);

    public static final Pattern EXTERNAL_SUFFIX = Pattern.compile("^/https?://", Pattern.CASE_INSENSITIVE);

    protected List<ProxyService> instances = Collections.synchronizedList(new ArrayList<>());

    @Reference(service = ProxyService.class, policy = ReferencePolicy.DYNAMIC, cardinality = ReferenceCardinality.MULTIPLE)
    protected void addProxyService(@Nonnull final ProxyService service) {
        LOG.info("addProxyService: {}", service.getName());
        instances.add(service);
    }

    protected void removeProxyService(@Nonnull final ProxyService service) {
        LOG.info("removeProxyService: {}", service.getName());
        instances.remove(service);
    }

    @Override
    protected void doGet(@Nonnull final SlingHttpServletRequest request,
                         @Nonnull final SlingHttpServletResponse response)
            throws IOException {
        RequestPathInfo pathInfo = request.getRequestPathInfo();
        String targetSuffix = pathInfo.getSuffix();
        if (StringUtils.isNotBlank(targetSuffix)) {
            String targetUrl = EXTERNAL_SUFFIX.matcher(targetSuffix).matches()
                    ? targetSuffix.substring(1) : targetSuffix;
            String queryString = request.getQueryString();
            if (StringUtils.isNotBlank(queryString)) {
                targetUrl += "?" + queryString;
            }
            for (ProxyService service : instances) {
                if (service.doProxy(request, response, targetUrl)) {
                    break;
                }
            }
        }
    }
}
