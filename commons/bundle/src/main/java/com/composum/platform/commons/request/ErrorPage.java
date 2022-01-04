package com.composum.platform.commons.request;

import com.composum.sling.core.AbstractSlingBean;
import com.composum.sling.core.CoreConfiguration;
import com.composum.sling.core.util.SlingUrl;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import java.io.IOException;
import java.security.Principal;

/**
 * Model for Errorpages, mainly containing logic about redirections to login.
 */
public class ErrorPage extends AbstractSlingBean {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorPage.class);

    /**
     * Parameter that is appended to {@link #getLoginUrl()} to save the current rendered resource, to allow
     * re-rendering it after the user logged in.
     */
    public static final String TARGET_PARAMETER = "target";

    /**
     * @see #getLoginUrl()
     */
    private transient String loginUrl;

    /**
     * @see #getCoreConfiguration()
     */
    private transient CoreConfiguration coreConfiguration;

    /**
     * The {@link CoreConfiguration} service.
     */
    public CoreConfiguration getCoreConfiguration() {
        if (coreConfiguration == null) {
            coreConfiguration = context.getService(CoreConfiguration.class);
        }
        return coreConfiguration;
    }


    /**
     * URL to login page.
     *
     * @see {@link CoreConfiguration#getLoginUrl()}
     */
    @NotNull
    public String getLoginUrl() {
        if (loginUrl == null) {
            loginUrl = getCoreConfiguration() != null ? getCoreConfiguration().getLoginUrl() : null;
            loginUrl = StringUtils.defaultIfBlank(loginUrl, "/system/sling/form/login.html");
        }
        return loginUrl;
    }

    /**
     * Redirects to the {@link #getLoginUrl()} if this isn't already a request to that.
     */
    public boolean redirectToLogin(SlingHttpServletRequest request, SlingHttpServletResponse response) {
        try {
            String requestUrl = request.getRequestURL().toString();
            if (!requestUrl.startsWith(getLoginUrl())) {
                SlingUrl url = new SlingUrl(request).fromUrl(getLoginUrl()).parameter(TARGET_PARAMETER, requestUrl);
                response.sendRedirect(url.toString());
                return true;
            }
        } catch (IOException ex) {
            LOG.error(ex.getMessage(), ex);
        }
        return false;
    }

    /**
     * Redirects to the {@link #getLoginUrl()} if user is not logged in yet and this isn't already a request to that.
     */
    public boolean loginIfAnonymous(SlingHttpServletRequest request, SlingHttpServletResponse response) {
        Principal currentUser = request.getUserPrincipal();
        if (currentUser == null || "anonymous".equals(currentUser.getName())) {
            return redirectToLogin(request, response);
        }
        return false;
    }


}
