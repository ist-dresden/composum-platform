package com.composum.sling.platform.staging.impl;


import com.composum.sling.platform.staging.Release;
import com.composum.sling.platform.staging.ReleaseMapper;
import com.composum.sling.platform.staging.StagingReleaseManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.ServletResolver;
import org.apache.sling.api.wrappers.SlingHttpServletRequestWrapper;
import org.apache.sling.engine.EngineConstants;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
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
import java.util.List;
import java.util.regex.Pattern;


/** A servlet filter to select the resource resolver for the release requested by access mode, or the URL parameters " + ResourceResolverChangeFilter.PARAM_CPM_RELEASE
 + " or " + ResourceResolverChangeFilter.PARAM_CPM_VERSION. */
@Component(
        service = {Filter.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Release Resolver Filter",
                EngineConstants.SLING_FILTER_SCOPE + "=" + EngineConstants.FILTER_SCOPE_REQUEST,
                Constants.SERVICE_RANKING + ":Integer=5050",
        }
)
@Designate(ocd = ResourceResolverChangeFilter.Configuration.class)
public class ResourceResolverChangeFilter implements Filter, ReleaseMapper {

    private static Logger LOGGER = LoggerFactory.getLogger(ResourceResolverChangeFilter.class);

    public static final String PARAM_CPM_RELEASE = "cpm.release";
    public static final String ATTR_CPM_RELEASE = "composum-platform-release-label";

    public static final String PARAM_CPM_VERSION = "cpm.version";
    public static final String ATTR_CPM_VERSION = "composum-platform-version-number";

    @Reference
    private ServletResolver servletResolver;

    @Reference
    private StagingReleaseManager releaseManager;

    private volatile Configuration configuration;

    private volatile boolean enabled;
    private List<Pattern> allowUriPatterns;
    private List<Pattern> allowPathPatterns;
    private List<Pattern> denyUriPatterns;
    private List<Pattern> denyPathPatterns;

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
                            Release release = releaseManager.findRelease(requestedResource, releaseNumber);
                            final ResourceResolver stagingResourceResolver =
                                    releaseManager.getResolverForRelease(release, this, true);
                            toClose = stagingResourceResolver;
                            switchResolver(slingRequestImpl, stagingResourceResolver);

                            LOGGER.info("ResourceResolver changed to StagingResourceResolver, release: '"
                                    + releaseNumber + "'");
                        } catch (StagingReleaseManager.ReleaseNotFoundException e) {
                            LOGGER.warn("Release {} not found for {}", releaseNumber, path);
                        } catch (StagingReleaseManager.ReleaseRootNotFoundException e) {
                            LOGGER.warn("Release root not found for {}", path);
                        }

                    } else {
                        final String versionId =
                                (String) request.getAttribute(ResourceResolverChangeFilter.ATTR_CPM_VERSION);

                        if (StringUtils.isNotBlank(versionId)) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("using version '" + versionId + "'...");
                            }

                            try {
                                VersionSelectResourceResolver versionSelectResourceResolver = new VersionSelectResourceResolver(resourceResolver, true, versionId);
                                toClose = versionSelectResourceResolver;
                                switchResolver(slingRequestImpl, versionSelectResourceResolver);
                            } catch (RepositoryException e) {
                                LOGGER.error("Version not found: " + versionId, e);
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
    public void activate(ComponentContext context, Configuration configuration) {
        this.configuration = configuration;
        enabled = false;
        allowUriPatterns = new ArrayList<>();
        for (String rule : configuration.pages_release_uri_allow()) {
            if (StringUtils.isNotBlank(rule = rule.trim())) allowUriPatterns.add(Pattern.compile(rule));
        }
        allowPathPatterns = new ArrayList<>();
        for (String rule : configuration.pages_release_path_allow()) {
            if (StringUtils.isNotBlank(rule = rule.trim())) allowPathPatterns.add(Pattern.compile(rule));
        }
        denyUriPatterns = new ArrayList<>();
        for (String rule : configuration.pages_release_uri_deny()) {
            if (StringUtils.isNotBlank(rule = rule.trim())) denyUriPatterns.add(Pattern.compile(rule));
        }
        denyPathPatterns = new ArrayList<>();
        for (String rule : configuration.pages_release_path_deny()) {
            if (StringUtils.isNotBlank(rule = rule.trim())) denyPathPatterns.add(Pattern.compile(rule));
        }
        enabled = configuration.staging_resolver_enabled();
        LOGGER.info("enabled: " + enabled);
    }

    @Deactivate
    public void deactivate() {
        enabled = false;
        configuration = null;
    }

    @ObjectClassDefinition(name = "Composum Platform Release Resolver Filter",
            description = "A servlet filter to select the resource resolver for the release requested by access mode, or the URL parameters " + ResourceResolverChangeFilter.PARAM_CPM_RELEASE
                    + " or " + ResourceResolverChangeFilter.PARAM_CPM_VERSION + ".")
    protected @interface Configuration {

        @AttributeDefinition(name = "enabled", description = "The on/off switch for the Release Resolver Filter")
        boolean staging_resolver_enabled() default true;

        @AttributeDefinition(name = "Allow release mapping (URI)", description = "The whitelist of URI patterns for release mapping. Regular expressions, matching the full URI (without query parameters).")
        String[] pages_release_uri_allow() default {};


        @AttributeDefinition(name = "Allow release mapping (path)", description = "The whitelist of PATH patterns for release mapping. Regular expressions, matching the absolute path.")
        String[] pages_release_path_allow() default {};

        @AttributeDefinition(name = "Deny release mapping (URI)", description = "The blacklist of URI patterns for release mapping. Regular expressions, matching the full URI (without query parameters).")
        String[] pages_release_uri_deny() default {"^.*/_jcr_content\\.token\\.png(/.+)?$", // Pages statistics tokens...
                "^/bin/(browser|packages|users|assets|pages)\\.html(/.*)?$",
                "^/(bin/cpm|servlet|system)/.*$"};

        @AttributeDefinition(name = "Deny release mapping (path)", description = "The blacklist of PATH patterns for release mapping. Regular expressions, matching the absolute path.")
        String[] pages_release_path_deny() default {"^/(apps|libs|etc|var)/.*$",
                "^/(bin/cpm|servlet|system)/.*$"};

    }
}
