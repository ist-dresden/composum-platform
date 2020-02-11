package com.composum.platform.commons.proxy.impl;

import com.composum.platform.commons.proxy.ProxyConfiguration;
import com.composum.platform.commons.proxy.ProxyService;
import com.composum.platform.commons.proxy.TagFilteringReader;
import com.composum.sling.core.util.ValueEmbeddingReader;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.ServiceScope;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * a configurable proxy service factory
 */
@Component(service = ProxyService.class, scope = ServiceScope.PROTOTYPE)
@Designate(ocd = ProxyConfiguration.class, factory = true)
public class ProxyServiceImpl implements ProxyService {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyServiceImpl.class);

    protected ProxyConfiguration config;

    protected Pattern targetPattern;

    @Activate
    @Modified
    protected void activate(final ProxyConfiguration config) {
        this.config = config;
        if (config.enabled()) {
            targetPattern = Pattern.compile(config.targetPattern());
        }
    }

    @Override
    @Nonnull
    public String getName() {
        return config.name();
    }


    /**
     * Handles the proxy request if appropriate (target pattern matches and access allowed)
     *
     * @param request   the proxy request
     * @param response  the response for the answer
     * @param targetUrl the url of the request which is addressing the target
     * @return 'true' if the request is supported by the service, allowed for the user and handle by the service
     */
    public boolean doProxy(@Nonnull final SlingHttpServletRequest request,
                           @Nonnull final SlingHttpServletResponse response,
                           @Nonnull final String targetUrl)
            throws IOException {
        Matcher matcher = targetPattern.matcher(targetUrl);
        if (matcher.find()) {
            try {
                String referencePath = config.referencePath();
                if (StringUtils.isNotBlank(referencePath)) {
                    ResourceResolver resolver = request.getResourceResolver();
                    if (resolver.getResource(referencePath) == null) {
                        return false; // access not allowed
                    }
                }
                doRequest(request, response, targetUrl, matcher);
            } catch (Exception ex) {
                LOG.error(ex.toString());
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            return true;
        }
        return false;
    }

    protected void doRequest(@Nonnull final SlingHttpServletRequest request,
                             @Nonnull final SlingHttpServletResponse response,
                             @Nonnull final String targetRef,
                             @Nonnull final Matcher matcher)
            throws Exception {
        String targetUrl = getTargetUrl(request, targetRef, matcher);
        if (StringUtils.isNotBlank(targetUrl)) {
            CloseableHttpClient client = HttpClients.createDefault();
            HttpGet httpGet = new HttpGet(targetUrl);
            LOG.info("proxy request '{}'", httpGet.getRequestLine());
            try (CloseableHttpResponse targetResponse = client.execute(httpGet)) {
                final HttpEntity entity = targetResponse.getEntity();
                if (entity != null) {
                    try (InputStream inputStream = entity.getContent()) {
                        IOUtils.copy(new TagFilteringReader(inputStream,
                                        config.tags_to_strip(), config.tags_to_drop()),
                                response.getWriter());
                    }
                } else {
                    LOG.warn("response is NULL ({})", targetUrl);
                }
            }
        } else {
            LOG.info("no target URL: NOP ({})", targetRef);
        }
    }

    @Nullable
    protected String getTargetUrl(@Nonnull final SlingHttpServletRequest request,
                                  @Nonnull final String targetRef, @Nonnull final Matcher matcher) {
        String targetUrl = config.targetUrl();
        if (StringUtils.isNotBlank(targetUrl)) {
            Map<String, Object> properties = new HashMap<>();
            properties.put("url", targetRef);
            for (int i = 0; i < matcher.groupCount(); i++) {
                properties.put("" + i, matcher.group(i));
            }
            ValueEmbeddingReader reader = new ValueEmbeddingReader(new StringReader(targetUrl), properties);
            try {
                targetUrl = IOUtils.toString(reader);
            } catch (IOException ex) {
                LOG.error(ex.toString());
                targetUrl = null;
            }
        } else {
            targetUrl = targetUrl.startsWith("/")
                    ? (request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + targetRef)
                    : targetRef;
        }
        return targetUrl;
    }
}
