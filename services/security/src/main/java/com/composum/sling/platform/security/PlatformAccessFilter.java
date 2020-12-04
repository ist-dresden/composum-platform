/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.sling.platform.security;

import com.composum.platform.commons.request.AccessMode;
import com.composum.sling.core.util.LinkMapper;
import com.composum.sling.core.util.LinkUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.Session;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.composum.platform.commons.request.AccessMode.ACCESS_MODE_AUTHOR;
import static com.composum.platform.commons.request.AccessMode.ACCESS_MODE_PREVIEW;
import static com.composum.platform.commons.request.AccessMode.ACCESS_MODE_PUBLIC;
import static com.composum.platform.commons.request.AccessMode.RA_ACCESS_MODE;

@Component(
        service = {Filter.class, PlatformAccessService.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Access Filter",
                "sling.filter.scope=REQUEST",
                "service.ranking:Integer=" + 5090
        },
        immediate = true
)
@Designate(ocd = PlatformAccessFilter.Config.class)
public class PlatformAccessFilter implements Filter, PlatformAccessService {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformAccessFilter.class);

    public static final String RA_IS_INTERNAL_REQUEST = "composum.platform.isInternalRequest";

    // access filter keys
    public static final String ACCESS_MODE_PARAM = "cpm.access";

    @ObjectClassDefinition(
            name = "Composum Platform Access Filter Configuration",
            description = "If enabled, this filter distinguishes between author, preview and public hosts and" +
                    " provides access protection based on these modi configurable in detail."
    )
    @interface Config {

        @AttributeDefinition(
                name = "Access restriction ON",
                description = "the on/off switch for the Access Filter restricted mode (reject unauthorized requests)"
        )
        boolean access_filter_enabled() default false;

        @AttributeDefinition(
                name = "Honor Sling Runmode",
                description = "use an 'author' or 'preview'/'public' Sling runmode to detect the access mode"
        )
        boolean honor_sling_runmode() default false;

        @AttributeDefinition(
                name = "Author URL mapping enabled",
                description = "if enabled the resolver mapping is used in author mode; default: false"
        )
        boolean enable_author_mapping() default false;

        @AttributeDefinition(
                name = "Author Hosts",
                description = "hostname patterns to detect authoring access requests"
        )
        String[] author_host_patterns() default {
                "^localhost$",
                "^192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}$",
                "^172\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$",
                "^10\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$",
                "^(apps|author)(\\..*)?$"
        };

        @AttributeDefinition(
                name = "Preview Hosts",
                description = "hostname patterns to detect preview access requests"
        )
        String[] preview_host_patterns() default {
                "^(preview)(\\..*)?$"
        };

        @AttributeDefinition(
                name = "Authoring URIs",
                description = "uri patterns which are always editing requests"
        )
        String[] author_uri_patterns() default {
                "^/bin/(?!(public|cpm/platform/auth)/).*$"
        };

        @AttributeDefinition(
                name = "Allow anonymous on Author",
                description = "URI patterns allowed for public access (to enable the login function)"
        )
        String[] author_allow_anonymous() default {
                "^/apps/.*\\.(css|js|svg|jpg|jpeg|png|gif|ico|ttf|woff2?)$",
                "^/bin/public/clientlibs\\.(min\\.)?(css|js)(/.*)?$",
                "^/libs(/jslibs)?/.*\\.(js|css|map)$",
                "^/(libs/)?fonts/.*\\.(css|eot|svg|ttf|woff2?)$",
                "^/libs(/composum/platform/public)?/login.*\\.(html|css|js|png)$",
                "^/j_security_check$",
                "^/favicon.ico$",
                "^/bin/cpm/platform/auth/sessionTransferCallback$"
        };

        @AttributeDefinition(
                name = "Author URI whitelist",
                description = "the general whitelist URI patterns for an authoring host"
        )
        String[] author_uri_allow() default {};

        @AttributeDefinition(
                name = "Author path whitelist",
                description = "the general whitelist PATH patterns for an authoring host"
        )
        String[] author_path_allow() default {};

        @AttributeDefinition(
                name = "Author URI blacklist",
                description = "the general blacklist URI patterns for an authoring host"
        )
        String[] author_uri_deny() default {};

        @AttributeDefinition(
                name = "Author path blacklist",
                description = "the general blacklist PATH patterns for an authoring host"
        )
        String[] author_path_deny() default {};

        @AttributeDefinition(
                name = "Public URI whitelist",
                description = "the general whitelist URI patterns for a public host"
        )
        String[] public_uri_allow() default {
                "^/(sitemap\\.)?robots\\.txt$",
                "^/sitemap\\.xml$",
                "^/favicon\\.ico$",
                "^/bin/public/clientlibs\\.(min\\.)?(css|js)(/.*)?$"
        };

        @AttributeDefinition(
                name = "Public path whitelist",
                description = "the general whitelist PATH patterns for a public host"
        )
        String[] public_path_allow() default {
                "^/apps/.*\\.(css|js|svg|jpg|jpeg|png|gif|ico|ttf|woff2?)$",
                "^/libs/sling/servlet/errorhandler/.*$",
                "^/libs/(fonts|jslibs|themes)/.*$",
                "^/libs(/composum/platform/public)?/login.*$"
        };

        @AttributeDefinition(
                name = "Public URI blacklist",
                description = "the general blacklist URI patterns for a public host"
        )
        String[] public_uri_deny() default {
                "^.*\\.(jsp|xml|json)$",
                "^.*/[^/]*\\.explorer\\..*$",
                "^/(servlet|system)(/.*)?$",
                "^/bin/(?!public/).*$"
        };

        @AttributeDefinition(
                name = "Public path blacklist",
                description = "the general blacklist PATH patterns for a public host"
        )
        String[] public_path_deny() default {
                "^/(etc|sightly|home)(/.*)?$",
                "^/(jcr:system|oak:index)(/.*)?$",
                "^.*/(rep:policy)(/.*)?$"
        };
    }

    @Reference
    private SlingSettingsService slingSettings;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private volatile PlatformAccessFilterAuthPlugin authPlugin;

    /**
     * the configuration patterns transformed into Pattern lists...
     */
    private List<Pattern> authorHostPatterns;
    private List<Pattern> authorUriPatterns;
    private List<Pattern> authorAllowAnonUriPatterns;
    private List<Pattern> authorAllowUriPatterns;
    private List<Pattern> authorAllowPathPatterns;
    private List<Pattern> authorDenyUriPatterns;
    private List<Pattern> authorDenyPathPatterns;
    private List<Pattern> publicAllowUriPatterns;
    private List<Pattern> publicAllowPathPatterns;
    private List<Pattern> publicDenyUriPatterns;
    private List<Pattern> publicDenyPathPatterns;
    private List<Pattern> previewHostPatterns;

    private Config config;

    /**
     * a thread local state object to make the requests context available in resolver-less interface methods
     */
    public static final class PlatformAccessContext implements AccessContext {

        public final SlingHttpServletRequest request;
        public final SlingHttpServletResponse response;
        public final ResourceResolver resolver;

        private PlatformAccessContext(@Nonnull final SlingHttpServletRequest request,
                                      @Nonnull final SlingHttpServletResponse response) {
            this(request, response, request.getResourceResolver());
        }

        private PlatformAccessContext(@Nonnull final SlingHttpServletRequest request,
                                      @Nonnull final SlingHttpServletResponse response,
                                      @Nonnull final ResourceResolver resolver) {
            this.request = request;
            this.response = response;
            this.resolver = resolver;
        }

        @Override
        @Nonnull
        public final SlingHttpServletRequest getRequest() {
            return request;
        }

        @Override
        @Nonnull
        public final SlingHttpServletResponse getResponse() {
            return response;
        }

        @Override
        @Nonnull
        public final ResourceResolver getResolver() {
            return resolver;
        }
    }

    private static final ThreadLocal<AccessContext> PLATFORM_REQUEST_THREAD_LOCAL = new ThreadLocal<>();

    private boolean handleUnauthorized(SlingHttpServletRequest request, SlingHttpServletResponse response,
                                       FilterChain chain, String reason, Object... args)
            throws ServletException, IOException {
        if (authPlugin != null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("AUTH plugin.triggerAuthentication... (" + reason + ")", args);
            }
            return authPlugin.triggerAuthentication(request, response, chain);
        } else {
            LOG.warn(reason, args);
            sendError(response, SlingHttpServletResponse.SC_UNAUTHORIZED);
            return true;
        }
    }

    @Override
    public final void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
        SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;

        AccessMode accessMode = getAccessMode(slingRequest, slingResponse);
        slingRequest.setAttribute(RA_ACCESS_MODE, accessMode.name());

        if (authPlugin != null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("AUTH plugin.examineRequest...");
            }
            if (authPlugin.examineRequest(slingRequest, slingResponse, chain)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("AUTH plugin.examineRequest: chain ends.");
                }
                return;
            }
        }

        ResourceResolver resolver = slingRequest.getResourceResolver();
        Resource resource = slingRequest.getResource();
        String path = resource.getPath();
        String uri = slingRequest.getRequestURI();
        String context = slingRequest.getContextPath();
        if (uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }

        if (config.access_filter_enabled()) {

            /*
            boolean isForwardedSSL = "on".equals(slingRequest.getHeader(LinkUtil.FORWARDED_SSL_HEADER));
            if (isForwardedSSL && slingRequest.getServerPort() == 80) {
                request = slingRequest = new ForwardedSSLRequestWrapper(slingRequest);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("SSL forward: " + request.getServerPort());
                }
            }
            */

            if (LOG.isDebugEnabled()) {
                LOG.debug(">> " + accessMode + " - "
                        + slingRequest.getScheme() + "://" + slingRequest.getServerName() + ":" + slingRequest.getServerPort() + slingRequest.getPathInfo()
                        + " (URI: " + uri + ", path: " + path + ", resource: " + resource
                        + ", secure: " + request.isSecure() + ", SSL?: " + LinkUtil.isForwaredSSL(slingRequest) + ")");
            }

            if (accessMode == AccessMode.PUBLIC) {

                Boolean isInternalRequest = (Boolean) request.getAttribute(RA_IS_INTERNAL_REQUEST);
                if (isInternalRequest == null || !isInternalRequest) {

                    if (isAccessDenied(path, true, publicAllowPathPatterns, publicDenyPathPatterns)) {
                        LOG.warn("REJECT(path): '" + path + "' by public path patterns!");
                        sendError(slingResponse, SlingHttpServletResponse.SC_NOT_FOUND);
                        return;
                    }

                    if (isAccessDenied(uri, true, publicAllowUriPatterns, publicDenyUriPatterns)) {
                        LOG.warn("REJECT(URI): '" + uri + "' by public URI patterns!");
                        sendError(slingResponse, SlingHttpServletResponse.SC_NOT_FOUND);
                        return;
                    }
                }

                if (LOG.isDebugEnabled()) {
                    LOG.debug("public: '{}' ({})", uri, path);
                }

            } else {

                if (isAccessDenied(path, true, authorAllowPathPatterns, authorDenyPathPatterns)) {
                    LOG.warn("REJECT(path): '" + path + "' by author path patterns!");
                    sendError(slingResponse, SlingHttpServletResponse.SC_NOT_FOUND);
                    return;
                }

                if (isAccessDenied(uri, true, authorAllowUriPatterns, authorDenyUriPatterns)) {
                    LOG.warn("REJECT(URI): '" + uri + "' by author URI patterns!");
                    sendError(slingResponse, SlingHttpServletResponse.SC_NOT_FOUND);
                    return;
                }

                // for authoring or preview access the user must be authenticated

                Session session = resolver.adaptTo(Session.class);
                if (session != null) {

                    String userId = session.getUserID();
                    if (userId == null || "anonymous".equalsIgnoreCase(userId)) {

                        if (isAccessDenied(uri, false, authorAllowAnonUriPatterns, null)) {
                            if (handleUnauthorized(slingRequest, slingResponse, chain,
                                    "REJECT(anon): '{}' by anonymous URI patterns!", uri)) {
                                return;
                            }
                        }
                    }

                } else {
                    if (handleUnauthorized(slingRequest, slingResponse, chain,
                            "REJECT(fault): '{}' - not adaptable to a session!", uri)) {
                        return;
                    }
                }
            }
        }

        if (accessMode == AccessMode.AUTHOR) {
            LinkMapper mapper = config.enable_author_mapping() ? LinkMapper.RESOLVER : LinkMapper.CONTEXT;
            request.setAttribute(LinkMapper.LINK_MAPPER_REQUEST_ATTRIBUTE, mapper);
            if (LOG.isDebugEnabled()) {
                LOG.debug("author: '{}' ({})", uri, path);
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("chain.doFilter()...");
        }
        try {
            PLATFORM_REQUEST_THREAD_LOCAL.set(new PlatformAccessContext(slingRequest, slingResponse));
            chain.doFilter(request, response);
        } finally {
            PLATFORM_REQUEST_THREAD_LOCAL.remove();
        }
    }

    @Override
    @Nullable
    public final AccessContext getAccessContext() {
        return PLATFORM_REQUEST_THREAD_LOCAL.get();
    }

    private AccessMode getAccessMode(SlingHttpServletRequest request,
                                     SlingHttpServletResponse response) {

        AccessMode accessMode = null;
        AccessMode value;

        value = AccessMode.accessModeValue(request.getParameter(ACCESS_MODE_PARAM));
        if (value != null) {
            if (isAuthorHost(request)) {
                accessMode = value;
                HttpSession httpSession = request.getSession(true);
                if (httpSession != null) {
                    httpSession.setAttribute(RA_ACCESS_MODE, accessMode);
                    if (LOG.isInfoEnabled()) {
                        LOG.info("session access mode (" + RA_ACCESS_MODE + ") set to: " + accessMode);
                    }
                }
            }
        }

        if (accessMode == null) {
            if (!isAuthorUri(request)) {
                // uses session state only if it's not an author URI like '/bin/pages/...'
                // otherwise you can lost the editing context for a session
                HttpSession httpSession = request.getSession(false);
                if (httpSession != null) {
                    value = AccessMode.accessModeValue(httpSession.getAttribute(RA_ACCESS_MODE));
                    if (value != null && isAuthorHost(request)) {
                        accessMode = value;
                    }
                }
            }
        }

        if (accessMode == null) {
            if (isAuthorHost(request)) {
                accessMode = AccessMode.AUTHOR;
            } else if (isPreviewHost(request)) {
                accessMode = AccessMode.PREVIEW;
            }
        }

        return accessMode != null ? accessMode : AccessMode.PUBLIC;
    }

    private boolean isAuthorHost(SlingHttpServletRequest request) {
        if (config.honor_sling_runmode()) {
            Set<String> runmodes = slingSettings.getRunModes();
            for (String runmode : runmodes) {
                if (ACCESS_MODE_AUTHOR.equalsIgnoreCase(runmode)) {
                    return true;
                } else if (ACCESS_MODE_PUBLIC.equalsIgnoreCase(runmode)
                        || ACCESS_MODE_PREVIEW.equalsIgnoreCase(runmode)) {
                    return false;
                }
            }
        }
        String host = request.getServerName();
        for (Pattern pattern : authorHostPatterns) {
            if (pattern.matcher(host).matches()) {
                return true;
            }
        }
        return false;
    }

    private boolean isPreviewHost(SlingHttpServletRequest request) {
        if (config.honor_sling_runmode()) {
            Set<String> runmodes = slingSettings.getRunModes();
            for (String runmode : runmodes) {
                if (ACCESS_MODE_PREVIEW.equalsIgnoreCase(runmode)) {
                    return true;
                } else if (ACCESS_MODE_PUBLIC.equalsIgnoreCase(runmode)
                        || ACCESS_MODE_AUTHOR.equalsIgnoreCase(runmode)) {
                    return false;
                }
            }
        }
        String host = request.getServerName();
        for (Pattern pattern : previewHostPatterns) {
            if (pattern.matcher(host).matches()) {
                return true;
            }
        }
        return false;
    }

    private boolean isAuthorUri(SlingHttpServletRequest request) {
        String uri = request.getRequestURI();
        for (Pattern pattern : authorUriPatterns) {
            if (pattern.matcher(uri).matches()) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("Duplicates")
    private boolean isAccessDenied(String path, boolean defaultValue,
                                   List<Pattern> allow, List<Pattern> deny) {

        if (StringUtils.isNotBlank(path)) {

            if (allow != null) {
                for (Pattern pattern : allow) {
                    if (pattern.matcher(path).matches()) {
                        return false;
                    }
                }
            }

            if (deny != null) {
                for (Pattern pattern : deny) {
                    if (pattern.matcher(path).matches()) {
                        return true;
                    }
                }
            }
        }

        return !defaultValue;
    }

    protected void sendError(ServletResponse response, int statusCode) throws IOException {
        HttpServletResponse slingResponse = (HttpServletResponse) response;
        slingResponse.sendError(statusCode);
    }

    @Override
    public final void init(FilterConfig filterConfig) {
    }

    @Override
    public final void destroy() {
    }

    @Activate
    @Modified
    public final void activate(final Config config) {
        this.config = config;
        authorHostPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.author_host_patterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorHostPatterns.add(Pattern.compile(rule));
        }
        previewHostPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.preview_host_patterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) {
                previewHostPatterns.add(Pattern.compile(rule));
            }
        }
        authorUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.author_uri_patterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorUriPatterns.add(Pattern.compile(rule));
        }
        authorAllowAnonUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.author_allow_anonymous())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorAllowAnonUriPatterns.add(Pattern.compile(rule));
        }
        authorAllowUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.author_uri_allow())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorAllowUriPatterns.add(Pattern.compile(rule));
        }
        authorAllowPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.author_path_allow())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorAllowPathPatterns.add(Pattern.compile(rule));
        }
        authorDenyUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.author_uri_deny())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorDenyUriPatterns.add(Pattern.compile(rule));
        }
        authorDenyPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.author_path_deny())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorDenyPathPatterns.add(Pattern.compile(rule));
        }
        publicAllowUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.public_uri_allow())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicAllowUriPatterns.add(Pattern.compile(rule));
        }
        publicAllowPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.public_path_allow())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicAllowPathPatterns.add(Pattern.compile(rule));
        }
        publicDenyUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.public_uri_deny())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicDenyUriPatterns.add(Pattern.compile(rule));
        }
        publicDenyPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.public_path_deny())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicDenyPathPatterns.add(Pattern.compile(rule));
        }
    }
}
