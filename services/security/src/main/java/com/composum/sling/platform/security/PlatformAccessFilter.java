package com.composum.sling.platform.security;

import com.composum.sling.core.util.LinkMapper;
import com.composum.sling.core.util.LinkUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.apache.felix.scr.annotations.sling.SlingFilter;
import org.apache.felix.scr.annotations.sling.SlingFilterScope;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Session;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;
import java.util.regex.Pattern;

@SlingFilter(
        label = "Composum Platform Access Filter",
        description = "a servlet filter to restrict the access to the resources of the platform",
        scope = {SlingFilterScope.REQUEST},
        order = 5090,
        metatype = true)
public class PlatformAccessFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformAccessFilter.class);

    // access filter keys
    public static final String ACCESS_MODE_PARAM = "cpm.access";
    public static final String ACCESS_MODE_KEY = "composum-platform-access-mode";

    public enum AccessMode {

        AUTHOR, PUBLIC;

        public static AccessMode accessModeValue(Object value) {
            AccessMode mode = null;
            if (value != null) {
                try {
                    mode = Enum.valueOf(AccessMode.class, value.toString().trim().toUpperCase());
                } catch (IllegalArgumentException iaex) {
                    // ok, null...
                }
            }
            return mode;
        }

        public static AccessMode requestMode(HttpServletRequest request) {
            return accessModeValue(request.getAttribute(ACCESS_MODE_KEY));
        }
    }

    public static final String FILTER_ENABLED = "access.filter.enabled";
    @Property(
            name = FILTER_ENABLED,
            label = "enabled",
            description = "the on/off switch for the Access Filter",
            boolValue = true
    )
    private boolean enabled;

    public static final String ENABLE_AUTHOR_MAPPING = "author.mapping.enabled";
    @Property(
            name = ENABLE_AUTHOR_MAPPING,
            label = "author mapping",
            description = "if enabled the resolver mapping is used in author mode; default: false",
            boolValue = false
    )
    private boolean enableAuthorMapping;

    public static final String AUTHOR_HOST_PATTERNS = "author.hosts";
    @Property(
            name = AUTHOR_HOST_PATTERNS,
            label = "Author Host patterns",
            description = "hostname patterns to detect authoring access requests",
            value = {"^localhost$",
                    "^192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}$",
                    "^172\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$",
                    "^10\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$",
                    "^(apps|author)(\\..*)?$"},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> authorHostPatterns;

    public static final String AUTHOR_ALLOW_ANON_URI_PATTERNS = "author.allow.anonymous";
    @Property(
            name = AUTHOR_ALLOW_ANON_URI_PATTERNS,
            label = "Public on Author (URI)",
            description = "URI patterns allowed for public access (to enable the login function)",
            value = {"^/apps/.*\\.(css|js)$",
                    "^/libs(/jslibs)?/.*\\.(js|css|map)$",
                    "^/(libs/)?fonts/.*\\.(css|eot|svg|ttf|woff2?)$",
                    "^/libs(/composum/platform/security)?/login.*\\.(html|css|js|png)$",
                    "^/j_security_check$",
                    "^/favicon.ico$"},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> authorAllowAnonUriPatterns;

    public static final String AUTHOR_ALLOW_URI_PATTERNS = "author.uri.allow";
    @Property(
            name = AUTHOR_ALLOW_URI_PATTERNS,
            label = "Allow on Author (URI)",
            description = "the general whitelist URI patterns for an authoring host",
            value = {""},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> authorAllowUriPatterns;

    public static final String AUTHOR_ALLOW_PATH_PATTERNS = "author.path.allow";
    @Property(
            name = AUTHOR_ALLOW_PATH_PATTERNS,
            label = "Allow on Author (path)",
            description = "the general whitelist PATH patterns for an authoring host",
            value = {""},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> authorAllowPathPatterns;

    public static final String AUTHOR_DENY_URI_PATTERNS = "author.uri.deny";
    @Property(
            name = AUTHOR_DENY_URI_PATTERNS,
            label = "Deny on Author (URI)",
            description = "the general blacklist URI patterns for an authoring host",
            value = {""},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> authorDenyUriPatterns;

    public static final String AUTHOR_DENY_PATH_PATTERNS = "author.path.deny";
    @Property(
            name = AUTHOR_DENY_PATH_PATTERNS,
            label = "Deny on Author (path)",
            description = "the general blacklist PATH patterns for an authoring host",
            value = {""},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> authorDenyPathPatterns;

    public static final String PUBLIC_ALLOW_URI_PATTERNS = "public.uri.allow";
    @Property(
            name = PUBLIC_ALLOW_URI_PATTERNS,
            label = "Allow on Public (URI)",
            description = "the general whitelist URI patterns for a public host",
            value = {"^/robots\\.txt$",
                    "^/sitemap\\.xml$",
                    "^/favicon\\.ico$"},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> publicAllowUriPatterns;

    public static final String PUBLIC_ALLOW_PATH_PATTERNS = "public.path.allow";
    @Property(
            name = PUBLIC_ALLOW_PATH_PATTERNS,
            label = "Allow on Public (path)",
            description = "the general whitelist PATH patterns for a public host",
            value = {"^/apps/.*\\.(css|js)$",
                    "^/libs/sling/servlet/errorhandler/.*$",
                    "^/libs/(fonts|jslibs|themes)/.*$",
                    "^/libs(/composum/platform/security)?/login.*$"},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> publicAllowPathPatterns;

    public static final String PUBLIC_DENY_URI_PATTERNS = "public.uri.deny";
    @Property(
            name = PUBLIC_DENY_URI_PATTERNS,
            label = "Deny on Public (URI)",
            description = "the general blacklist URI patterns for a public host",
            value = {"^.*\\.(jsp|xml|json)$",
                    "^.*/[^/]*\\.explorer\\..*$",
                    "^/(bin/cpm|servlet|system)(/.*)?$",
                    "^/bin/(browser|packages|users|pages|assets).*$"},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> publicDenyUriPatterns;

    public static final String PUBLIC_DENY_PATH_PATTERNS = "public.path.deny";
    @Property(
            name = PUBLIC_DENY_PATH_PATTERNS,
            label = "Deny on Public (path)",
            description = "the general blacklist PATH patterns for a public host",
            value = {"^/(etc|sightly|home)(/.*)?$",
                    "^/(jcr:system|oak:index)(/.*)?$",
                    "^.*/(rep:policy)(/.*)?$"},
            unbounded = PropertyUnbounded.ARRAY
    )
    private List<Pattern> publicDenyPathPatterns;

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (enabled) {

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
                        + ", secure: " + request.isSecure() + ", SSL?: " + slingRequest.getHeader(LinkUtil.FORWARDED_SSL_HEADER) + ")");
            }

            if (accessMode == AccessMode.AUTHOR) {

                if (!checkAccessRules(path, true, authorAllowPathPatterns, authorDenyPathPatterns)) {
                    LOG.warn("REJECT(path): '" + path + "' by author path patterns!");
                    sendError(slingResponse, SlingHttpServletResponse.SC_NOT_FOUND);
                    return;
                }

                if (!checkAccessRules(uri, true, authorAllowUriPatterns, authorDenyUriPatterns)) {
                    LOG.warn("REJECT(URI): '" + uri + "' by author URI patterns!");
                    sendError(slingResponse, SlingHttpServletResponse.SC_NOT_FOUND);
                    return;
                }

                // for authoring access the user must be authenticated

                Session session = resolver.adaptTo(Session.class);
                String userId = session.getUserID();

                if (userId == null || "anonymous".equalsIgnoreCase(userId)) {

                    if (!checkAccessRules(uri, false, authorAllowAnonUriPatterns, null)) {
                        LOG.warn("REJECT(anon): '" + uri + "' by anonymous URI patterns!");
                        sendError(slingResponse, SlingHttpServletResponse.SC_UNAUTHORIZED);
                        return;
                    }
                }

                request.setAttribute(LinkMapper.LINK_MAPPER_REQUEST_ATTRIBUTE,
                        enableAuthorMapping ? LinkMapper.RESOLVER : LinkMapper.CONTEXT);

            } else {

                if (!checkAccessRules(path, true, publicAllowPathPatterns, publicDenyPathPatterns)) {
                    LOG.warn("REJECT(path): '" + path + "' by public path patterns!");
                    sendError(slingResponse, SlingHttpServletResponse.SC_NOT_FOUND);
                    return;
                }

                if (!checkAccessRules(uri, true, publicAllowUriPatterns, publicDenyUriPatterns)) {
                    LOG.warn("REJECT(URI): '" + uri + "' by public URI patterns!");
                    sendError(slingResponse, SlingHttpServletResponse.SC_NOT_FOUND);
                    return;
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
                HttpSession httpSession = request.getSession(false);
                if (httpSession != null) {
                    httpSession.setAttribute(ACCESS_MODE_KEY, accessMode);
                    if (LOG.isInfoEnabled()) {
                        LOG.info("session access mode (" + ACCESS_MODE_KEY + ") set to: " + accessMode);
                    }
                } else {
                    Cookie cookie = new Cookie(ACCESS_MODE_KEY, accessMode.name());
                    cookie.setMaxAge(24 * 60 * 60);
                    cookie.setPath("/");
                    response.addCookie(cookie);
                    if (LOG.isInfoEnabled()) {
                        LOG.info("access mode cookie (" + cookie.getName() + ") set to: " + cookie.getValue());
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
            Cookie cookie = request.getCookie(ACCESS_MODE_KEY);
            if (cookie != null) {
                value = AccessMode.accessModeValue(cookie.getValue());
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

    protected boolean checkAccessRules(String path, boolean defaultValue,
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

    protected void sendError(ServletResponse response, int statusCode) throws IOException {
        HttpServletResponse slingResponse = (HttpServletResponse) response;
        slingResponse.sendError(statusCode);
    }

    public void init(FilterConfig filterConfig) throws ServletException {
    }

    public void destroy() {
    }

    @Activate
    @Modified
    public void activate(ComponentContext context) {
        Dictionary<String, Object> properties = context.getProperties();
        enabled = PropertiesUtil.toBoolean(properties.get(FILTER_ENABLED), true);
        enableAuthorMapping = PropertiesUtil.toBoolean(properties.get(ENABLE_AUTHOR_MAPPING), false);
        authorHostPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(AUTHOR_HOST_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorHostPatterns.add(Pattern.compile(rule));
        }
        authorAllowAnonUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(AUTHOR_ALLOW_ANON_URI_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorAllowAnonUriPatterns.add(Pattern.compile(rule));
        }
        authorAllowUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(AUTHOR_ALLOW_URI_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorAllowUriPatterns.add(Pattern.compile(rule));
        }
        authorAllowPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(AUTHOR_ALLOW_PATH_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorAllowPathPatterns.add(Pattern.compile(rule));
        }
        authorDenyUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(AUTHOR_DENY_URI_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorDenyUriPatterns.add(Pattern.compile(rule));
        }
        authorDenyPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(AUTHOR_DENY_PATH_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) authorDenyPathPatterns.add(Pattern.compile(rule));
        }
        publicAllowUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(PUBLIC_ALLOW_URI_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicAllowUriPatterns.add(Pattern.compile(rule));
        }
        publicAllowPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(PUBLIC_ALLOW_PATH_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicAllowPathPatterns.add(Pattern.compile(rule));
        }
        publicDenyUriPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(PUBLIC_DENY_URI_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicDenyUriPatterns.add(Pattern.compile(rule));
        }
        publicDenyPathPatterns = new ArrayList<>();
        for (String rule : PropertiesUtil.toStringArray(properties.get(PUBLIC_DENY_PATH_PATTERNS))) {
            if (StringUtils.isNotBlank(rule = rule.trim())) publicDenyPathPatterns.add(Pattern.compile(rule));
        }
    }
}
