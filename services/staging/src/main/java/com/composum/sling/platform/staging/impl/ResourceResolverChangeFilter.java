package com.composum.sling.platform.staging.impl;


import com.composum.sling.platform.staging.ReleaseMapper;
import com.composum.sling.platform.staging.StagingReleaseManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.sling.SlingFilter;
import org.apache.felix.scr.annotations.sling.SlingFilterScope;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.ServletResolver;
import org.apache.sling.api.wrappers.SlingHttpServletRequestWrapper;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;
import java.util.regex.Pattern;

@SlingFilter(
        label = "Composum Platform Release Resolver Filter",
        description = "a servlet filter to select the resource resolver for the requested release",
        scope = {SlingFilterScope.REQUEST},
        order = 5050,
        metatype = true)
public class ResourceResolverChangeFilter implements Filter, ReleaseMapper {

    private static Logger LOGGER = LoggerFactory.getLogger(ResourceResolverChangeFilter.class);

    public static final String PARAM_CPM_RELEASE = "cpm.release";
    public static final String ATTR_CPM_RELEASE = "composum-platform-release-label";

    public static final String PARAM_CPM_VERSION = "cpm.version";
    public static final String ATTR_CPM_VERSION = "composum-platform-version-number";

    public static final String FILTER_ENABLED = "staging.resolver.enabled";
    @Property(
            name = FILTER_ENABLED,
            label = "enabled",
            description = "the on/off switch for the Release Resolver Filter",
            boolValue = true
    )
    private boolean enabled;

    public static final String ALLOW_URI_PATTERNS = "pages.release.uri.allow";
    @Property(
            name = ALLOW_URI_PATTERNS,
            label = "Allow release mapping (URI)",
            description = "the whitelist of URI patterns for release mapping",
            value = {""},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> allowUriPatterns;

    public static final String ALLOW_PATH_PATTERNS = "pages.release.path.allow";
    @Property(
            name = ALLOW_PATH_PATTERNS,
            label = "Allow release mapping (path)",
            description = "the whitelist of PATH patterns for release mapping",
            value = {""},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> allowPathPatterns;

    public static final String DENY_URI_PATTERNS = "pages.release.uri.deny";
    @Property(
            name = DENY_URI_PATTERNS,
            label = "Deny release mapping (URI)",
            description = "the blacklist of URI patterns for release mapping",
            value = {"^.*/_jcr_content\\.token\\.png(/.+)?$", // Pages statistics tokens...
                    "^/bin/(browser|packages|users|assets|pages)\\.html(/.*)?$",
                    "^/(bin/cpm|servlet|system)/.*$"},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> denyUriPatterns;

    public static final String DENY_PATH_PATTERNS = "pages.release.path.deny";
    @Property(
            name = DENY_PATH_PATTERNS,
            label = "Deny release mapping (path)",
            description = "the blacklist of PATH patterns for release mapping",
            value = {"^/(apps|libs|etc|var)/.*$",
                    "^/(bin/cpm|servlet|system)/.*$"},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> denyPathPatterns;

    @Reference
    private ResourceResolverFactory delegatee;

    @Reference
    private ServletResolver servletResolver;

    @Reference
    private StagingReleaseManager releaseManager;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

        ResourceResolver toClose = null;

        if (enabled) {
            SlingHttpServletRequest slingRequestImpl = determineRequestImpl(request);

            if (slingRequestImpl != null) {

                Resource requestedResource = slingRequestImpl.getResource();
                String path = requestedResource.getPath();
                String uri = slingRequestImpl.getRequestURI();
                final ResourceResolver resourceResolver = slingRequestImpl.getResourceResolver();

                if (releaseMappingAllowed(path, uri)) {

                    final String releasedLabel =
                            (String) request.getAttribute(ResourceResolverChangeFilter.ATTR_CPM_RELEASE);


                    if (StringUtils.isNotBlank(releasedLabel)) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("using release '" + releasedLabel + "'...");
                        }

                        String releaseNumber = StringUtils.removeStart(releasedLabel, "composum-release-");

                        try {
                            StagingReleaseManager.Release release = releaseManager.findRelease(requestedResource, releaseNumber);
                            final ResourceResolver stagingResourceResolver =
                                    releaseManager.getResolverForRelease(release, this, true);
                            toClose = stagingResourceResolver;
                            switchResolver(slingRequestImpl, stagingResourceResolver);

                            LOGGER.info("ResourceResolver changed to StagingResourceResolver, release: '"
                                    + releaseNumber + "'");
                        } catch (StagingReleaseManager.ReleaseNotFoundException e) {
                            LOGGER.warn("Release {} not found for {}", releaseNumber, path);
                        }

                    } else {
                        final String versionNumber =
                                (String) request.getAttribute(ResourceResolverChangeFilter.ATTR_CPM_VERSION);

                        if (StringUtils.isNotBlank(versionNumber)) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("using version '" + versionNumber + "'...");
                            }

                            try {
                                VersionSelectResourceResolver versionSelectResourceResolver = new VersionSelectResourceResolver(resourceResolver, true, versionNumber);
                                toClose = versionSelectResourceResolver;
                                switchResolver(slingRequestImpl, versionSelectResourceResolver);
                            } catch (RepositoryException e) {
                                LOGGER.error("Version not found: " + versionNumber, e);
                            }
                        }
                    }
                }
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (toClose != null)
                toClose.close();
        }
    }

    protected void switchResolver(SlingHttpServletRequest slingRequestImpl, ResourceResolver stagingResourceResolver) throws ServletException {
        try {
            // if we try to cache this method object, we had to synchronize it - so leave it for now
            // org.apache.sling.engine.impl.SlingHttpServletRequestImpl.getRequestData()
            final Method getRequestData = slingRequestImpl.getClass().getMethod("getRequestData");
            final Object requestData = getRequestData.invoke(slingRequestImpl);

            // if we try to cache this method object, we had to synchronize it - so leave it for now
            // org.apache.sling.engine.impl.request.RequestData.initResource(ResourceResolver resourceResolver)
            final Method initResource = requestData.getClass()
                    .getMethod("initResource", ResourceResolver.class);
            final Object resource = initResource.invoke(requestData, stagingResourceResolver);

            // if we try to cache this method object, we had to synchronize it - so leave it for now
            // org.apache.sling.engine.impl.request.RequestData.initServlet(Resource resource, ServletResolver sr)
            final Method initServlet = requestData.getClass()
                    .getMethod("initServlet", Resource.class, ServletResolver.class);
            initServlet.invoke(requestData, resource, servletResolver);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            LOGGER.error("can not change ResourceResolver: ", e);
            throw new ServletException("Error switching ResourceResolver");
        }
    }

    private SlingHttpServletRequest determineRequestImpl(ServletRequest request) {
        while (request instanceof SlingHttpServletRequestWrapper) {
            request = ((SlingHttpServletRequestWrapper) request).getSlingRequest();
        }
        return request instanceof SlingHttpServletRequest
                ? (SlingHttpServletRequest) request
                : null;
    }

    @Override
    public boolean releaseMappingAllowed(String path, String uri) {
        return releaseMappingAllowed(path, true, allowPathPatterns, denyPathPatterns) &&
                releaseMappingAllowed(uri, true, allowUriPatterns, denyUriPatterns);
    }

    @Override
    public boolean releaseMappingAllowed(String path) {
        return releaseMappingAllowed(path, true, allowPathPatterns, denyPathPatterns);
    }

    @SuppressWarnings("Duplicates")
    private boolean releaseMappingAllowed(String path, boolean defaultValue,
                                          List<Pattern> allow, List<Pattern> deny) {
        if (StringUtils.isNotBlank(path)) {
            if (allow != null) {
                for (Pattern pattern : allow) {
                    if (pattern.matcher(path).matches()) {
                        return true;
                    }
                }
            }
            if (deny != null) {
                for (Pattern pattern : deny) {
                    if (pattern.matcher(path).matches()) {
                        return false;
                    }
                }
            }
        }
        return defaultValue;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void destroy() {
    }

    @Activate
    @Modified
    public void activate(ComponentContext context) {
        Dictionary<String, Object> properties = context.getProperties();
        enabled = (boolean) properties.get(FILTER_ENABLED);
        LOGGER.info("enabled: " + enabled);
        allowUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(ALLOW_URI_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) allowUriPatterns.add(Pattern.compile(rule));
        }
        allowPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(ALLOW_PATH_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) allowPathPatterns.add(Pattern.compile(rule));
        }
        denyUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(DENY_URI_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) denyUriPatterns.add(Pattern.compile(rule));
        }
        denyPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(DENY_PATH_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) denyPathPatterns.add(Pattern.compile(rule));
        }
    }
}
