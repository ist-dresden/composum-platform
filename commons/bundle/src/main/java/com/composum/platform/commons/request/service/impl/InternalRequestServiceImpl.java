package com.composum.platform.commons.request.service.impl;

import com.composum.platform.commons.request.service.InternalRequestService;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.engine.SlingRequestProcessor;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
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
    @NotNull
    public String getString(@NotNull SlingHttpServletRequest contextRequest, @NotNull PathInfo pathInfo)
            throws ServletException, IOException {
        MockSlingHttpServletResponse response = doGet(contextRequest, pathInfo);
        return response != null ? response.getOutputAsString() : "";
    }

    @Override
    @NotNull
    public byte[] getBytes(@NotNull SlingHttpServletRequest contextRequest, @NotNull PathInfo pathInfo)
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
    protected SlingResponse doGet(@NotNull SlingHttpServletRequest contextRequest, @NotNull PathInfo pathInfo)
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
