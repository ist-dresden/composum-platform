package com.composum.platform.commons.request.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.engine.SlingRequestProcessor;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;

@Component(
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Commons Internal Request Service"
        }
)
public class InternalRequestServiceImpl implements InternalRequestService {

    private static final Logger LOG = LoggerFactory.getLogger(InternalRequestServiceImpl.class);

    @Reference
    protected SlingRequestProcessor slingRequestProcessor;

    @Override
    @Nonnull
    public String getString(@Nonnull SlingHttpServletRequest contextRequest, @Nonnull PathInfo pathInfo)
            throws ServletException, IOException {
        MockSlingHttpServletResponse response = doGet(contextRequest, pathInfo);
        return response != null ? response.getOutputAsString() : "";
    }

    @Override
    @Nonnull
    public byte[] getBytes(@Nonnull SlingHttpServletRequest contextRequest, @Nonnull PathInfo pathInfo)
            throws ServletException, IOException {
        MockSlingHttpServletResponse response = doGet(contextRequest, pathInfo);
        return response != null ? response.getOutput() : new byte[0];
    }

    /**
     * gets the response of the internal request
     *
     * @param contextRequest the 'original' rendering request
     * @param pathInfo       the prepared path info object for the internal request
     */
    protected SlingResponse doGet(@Nonnull SlingHttpServletRequest contextRequest, @Nonnull PathInfo pathInfo)
            throws ServletException, IOException {
        SlingResponse response = null;
        String path = pathInfo.getResourcePath();
        if (LOG.isDebugEnabled()) {
            LOG.debug("doGet({})", path);
        }
        if (StringUtils.isNotBlank(path)) {
            SlingRequest request = new SlingRequest(contextRequest, pathInfo);
            request.setAttribute(RA_IS_INTERNAL_REQUEST, Boolean.TRUE);
            response = new SlingResponse();
            slingRequestProcessor.processRequest(request, response, contextRequest.getResourceResolver());
        }
        return response;
    }
}
