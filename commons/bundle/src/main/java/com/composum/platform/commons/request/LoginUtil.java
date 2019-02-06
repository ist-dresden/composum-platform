package com.composum.platform.commons.request;

import com.composum.sling.core.util.LinkUtil;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.Principal;

public class LoginUtil {

    private static final Logger LOG = LoggerFactory.getLogger(LoginUtil.class);

    public static final String LOGIN_URI = "/libs/composum/platform/public/login.html";
    public static final String LOGIN_URL = LOGIN_URI + "?resource=";

    public static boolean redirectToLogin(SlingHttpServletRequest request, SlingHttpServletResponse response) {
        try {
            String requestUri = request.getRequestURI();
            if (!requestUri.startsWith(LOGIN_URI)) {
                String url = LOGIN_URL + LinkUtil.encodePath(requestUri);
                response.sendRedirect(LinkUtil.getUrl(request, url));
                return true;
            }
        } catch (IOException ex) {
            LOG.error(ex.getMessage(), ex);
        }
        return false;
    }

    public static boolean loginIfAnonymous(SlingHttpServletRequest request, SlingHttpServletResponse response) {
        Principal currentUser = request.getUserPrincipal();
        if (currentUser == null || "anonymous".equals(currentUser.getName())) {
            return redirectToLogin(request, response);
        }
        return false;
    }
}
