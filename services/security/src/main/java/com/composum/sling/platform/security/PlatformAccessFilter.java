/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.sling.platform.security;

import com.composum.sling.core.util.LinkMapper;
import com.composum.sling.core.util.LinkUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.regex.Pattern;

@Component(
        service = {Filter.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Access Filter",
                "sling.filter.scope=REQUEST",
                "service.ranking:Integer=" + 5090
        },
        configurationPolicy = ConfigurationPolicy.REQUIRE
)
@Designate(ocd = PlatformAccessFilter.Config.class)
public class PlatformAccessFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformAccessFilter.class);

    public static final String RA_IS_INTERNAL_REQUEST = "composum.platform.isInternalRequest";

    // access filter keys
    public static final String ACCESS_MODE_PARAM = "cpm.access";
    public static final String ACCESS_MODE_KEY = "composum-platform-access-mode";

    @ObjectClassDefinition(
            name = "Composum Platform Access Filter Configuration"
    )
    @interface Config {

        @AttributeDefinition(
                name = "access.filter.enabled",
                description = "the on/off switch for the Access Filter"
        )
        boolean enabled() default false;

        @AttributeDefinition(
                name = "author.mapping.enabled",
                description = "if enabled the resolver mapping is used in author mode; default: false"
        )
        boolean enableAuthorMapping() default false;

        @AttributeDefinition(
                name = "author.hosts",
                description = "hostname patterns to detect authoring access requests"
        )
        String[] authorHostPatterns() default {
                "^localhost$",
                "^192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}$",
                "^172\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$",
                "^10\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$",
                "^(apps|author)(\\..*)?$"
        };

        @AttributeDefinition(
                name = "author.allow.anonymous",
                description = "URI patterns allowed for public access (to enable the login function)"
        )
        String[] authorAllowAnonUriPatterns() default {
                "^/apps/.*\\.(css|js)$",
                "^/bin/public/clientlibs\\.(min\\.)?(css|js)(/.*)?$",
                "^/libs(/jslibs)?/.*\\.(js|css|map)$",
                "^/(libs/)?fonts/.*\\.(css|eot|svg|ttf|woff2?)$",
                "^/libs(/composum/platform/security)?/login.*\\.(html|css|js|png)$",
                "^/j_security_check$",
                "^/favicon.ico$"
        };

        @AttributeDefinition(
                name = "author.uri.allow",
                description = "the general whitelist URI patterns for an authoring host"
        )
        String[] authorAllowUriPatterns() default {};

        @AttributeDefinition(
                name = "author.path.allow",
                description = "the general whitelist PATH patterns for an authoring host"
        )
        String[] authorAllowPathPatterns() default {};

        @AttributeDefinition(
                name = "author.uri.deny",
                description = "the general blacklist URI patterns for an authoring host"
        )
        String[] authorDenyUriPatterns() default {};

        @AttributeDefinition(
                name = "author.path.deny",
                description = "the general blacklist PATH patterns for an authoring host"
        )
        String[] authorDenyPathPatterns() default {};

        @AttributeDefinition(
                name = "public.uri.allow",
                description = "the general whitelist URI patterns for a public host"
        )
        String[] publicAllowUriPatterns() default {
                "^/robots\\.txt$",
                "^/sitemap\\.xml$",
                "^/favicon\\.ico$",
                "^/bin/public/clientlibs\\.(min\\.)?(css|js)(/.*)?$"
        };

        @AttributeDefinition(
                name = "public.path.allow",
                description = "the general whitelist PATH patterns for a public host"
        )
        String[] publicAllowPathPatterns() default {
                "^/apps/.*\\.(css|js)$",
                "^/libs/sling/servlet/errorhandler/.*$",
                "^/libs/(fonts|jslibs|themes)/.*$",
                "^/libs(/composum/platform/security)?/login.*$"
        };

        @AttributeDefinition(
                name = "public.uri.deny",
                description = "the general blacklist URI patterns for a public host"
        )
        String[] publicDenyUriPatterns() default {
                "^.*\\.(jsp|xml|json)$",
                "^.*/[^/]*\\.explorer\\..*$",
                "^/(bin/cpm|servlet|system)(/.*)?$",
                "^/bin/(browser|packages|users|pages|assets).*$"
        };

        @AttributeDefinition(
                name = "public.path.deny",
                description = "the general blacklist PATH patterns for a public host"
        )
        String[] publicDenyPathPatterns() default {
                "^/(etc|sightly|home)(/.*)?$",
                "^/(jcr:system|oak:index)(/.*)?$",
                "^.*/(rep:policy)(/.*)?$"
        };
    }

    /**
     * the configuration patterns transformed into Pattern lists...
     */
    private List<Pattern> authorHostPatterns;
    private List<Pattern> authorAllowAnonUriPatterns;
    private List<Pattern> authorAllowUriPatterns;
    private List<Pattern> authorAllowPathPatterns;
    private List<Pattern> authorDenyUriPatterns;
    private List<Pattern> authorDenyPathPatterns;
    private List<Pattern> publicAllowUriPatterns;
    private List<Pattern> publicAllowPathPatterns;
    private List<Pattern> publicDenyUriPatterns;
    private List<Pattern> publicDenyPathPatterns;

    private Config config;

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (config.enabled()) {

            SlingHttpServletRequest slingRequest = (SlingHttpServletRequest) request;
            SlingHttpServletResponse slingResponse = (SlingHttpServletResponse) response;

            /*
            boolean isForwardedSSL = "on".equals(slingRequest.getHeader(LinkUtil.FORWARDED_SSL_HEADER));
            if (isForwardedSSL && slingRequest.getServerPort() == 80) {
                request = slingRequest = new ForwardedSSLRequestWrapper(slingRequest);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("SSL forward: " + request.getServerPort());
                }
            }
            */

            AccessMode accessMode = getAccessMode(slingRequest, slingResponse);

            slingRequest.setAttribute(ACCESS_MODE_KEY, accessMode.name());

            ResourceResolver resolver = slingRequest.getResourceResolver();
            Resource resource = slingRequest.getResource();
            String path = resource.getPath();
            String uri = slingRequest.getRequestURI();
            String context = slingRequest.getContextPath();
            if (uri.startsWith(context)) {
                uri = uri.substring(context.length());
            }

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

                // for authoring pr preview access the user must be authenticated

                Session session = resolver.adaptTo(Session.class);
                String userId = session.getUserID();

                if (userId == null || "anonymous".equalsIgnoreCase(userId)) {

                    if (isAccessDenied(uri, false, authorAllowAnonUriPatterns, null)) {
                        LOG.warn("REJECT(anon): '" + uri + "' by anonymous URI patterns!");
                        sendError(slingResponse, SlingHttpServletResponse.SC_UNAUTHORIZED);
                        return;
                    }
                }

                LinkMapper mapper = config.enableAuthorMapping() ? LinkMapper.RESOLVER : LinkMapper.CONTEXT;
                request.setAttribute(LinkMapper.LINK_MAPPER_REQUEST_ATTRIBUTE, mapper);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("author: '{}' ({})", uri, path);
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("chain.doFilter()...");
        }
        chain.doFilter(request, response);
    }

    protected AccessMode getAccessMode(SlingHttpServletRequest request,
                                       SlingHttpServletResponse response) {

        AccessMode accessMode = null;
        AccessMode value;

        value = AccessMode.accessModeValue(request.getParameter(ACCESS_MODE_PARAM));
        if (value != null) {
            if (isAuthorHost(request)) {
                accessMode = value;
                HttpSession httpSession = request.getSession(true);
                if (httpSession != null) {
                    httpSession.setAttribute(ACCESS_MODE_KEY, accessMode);
                    if (LOG.isInfoEnabled()) {
                        LOG.info("session access mode (" + ACCESS_MODE_KEY + ") set to: " + accessMode);
                    }
                }
            }
        }

        if (accessMode == null) {
            HttpSession httpSession = request.getSession(false);
            if (httpSession != null) {
                value = AccessMode.accessModeValue(httpSession.getAttribute(ACCESS_MODE_KEY));
                if (value != null && isAuthorHost(request)) {
                    accessMode = value;
                }
            }
        }

        if (accessMode == null) {
            if (isAuthorHost(request)) {
                accessMode = AccessMode.AUTHOR;
            }
        }

        return accessMode != null ? accessMode : AccessMode.PUBLIC;
    }

    protected boolean isAuthorHost(SlingHttpServletRequest request) {
        String host = request.getServerName();
        for (Pattern pattern : authorHostPatterns) {
            if (pattern.matcher(host).matches()) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("Duplicates")
    protected boolean isAccessDenied(String path, boolean defaultValue,
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

    public void init(FilterConfig filterConfig) {
    }

    public void destroy() {
    }

    @Activate
    @Modified
    public void activate(final Config config) {
        this.config = config;
        authorHostPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.authorHostPatterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorHostPatterns.add(Pattern.compile(rule));
        }
        authorAllowAnonUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.authorAllowAnonUriPatterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorAllowAnonUriPatterns.add(Pattern.compile(rule));
        }
        authorAllowUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.authorAllowUriPatterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorAllowUriPatterns.add(Pattern.compile(rule));
        }
        authorAllowPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.authorAllowPathPatterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorAllowPathPatterns.add(Pattern.compile(rule));
        }
        authorDenyUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.authorDenyUriPatterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorDenyUriPatterns.add(Pattern.compile(rule));
        }
        authorDenyPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.authorDenyPathPatterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorDenyPathPatterns.add(Pattern.compile(rule));
        }
        publicAllowUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.publicAllowUriPatterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicAllowUriPatterns.add(Pattern.compile(rule));
        }
        publicAllowPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.publicAllowPathPatterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicAllowPathPatterns.add(Pattern.compile(rule));
        }
        publicDenyUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.publicDenyUriPatterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicDenyUriPatterns.add(Pattern.compile(rule));
        }
        publicDenyPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(config.publicDenyPathPatterns())) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicDenyPathPatterns.add(Pattern.compile(rule));
        }
    }
}
