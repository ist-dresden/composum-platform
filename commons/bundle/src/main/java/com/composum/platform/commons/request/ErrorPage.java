package com.composum.platform.commons.request;

import com.composum.sling.core.AbstractSlingBean;
import com.composum.sling.core.CoreConfiguration;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Model for Errorpages, mainly containing logic about redirections to login.
 */
public class ErrorPage extends AbstractSlingBean {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorPage.class);

    /**
     * @see #getCoreConfiguration()
     */
    private volatile CoreConfiguration coreConfiguration;

    /**
     * The {@link CoreConfiguration} service.
     */
    protected CoreConfiguration getCoreConfiguration() {
        if (coreConfiguration == null) {
            coreConfiguration = context.getService(CoreConfiguration.class);
        }
        return coreConfiguration;
    }

    public boolean forwardToErrorpage(@Nonnull final SlingHttpServletRequest request,
                                      @Nonnull final SlingHttpServletResponse response,
                                      int statusCode)
            throws ServletException, IOException {
        final CoreConfiguration coreConfig = getCoreConfiguration();
        if (coreConfig != null) {
            return coreConfig.forwardToErrorpage(request, response, statusCode);
        }
        return false;
    }
}
