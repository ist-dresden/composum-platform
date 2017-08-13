package com.composum.platform.commons.request.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.engine.SlingRequestProcessor;
import org.apache.sling.servlethelpers.MockSlingHttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;

@SuppressWarnings("deprecation")
@Component(
        label = "Composum Platform Commons Internal Request Service",
        description = "performs requests to repository resources internal"
)
@Service
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
        if (StringUtils.isNotBlank(pathInfo.getResourcePath())) {
            SlingRequest request = new SlingRequest(contextRequest, pathInfo);
            response = new SlingResponse();
            slingRequestProcessor.processRequest(request, response, contextRequest.getResourceResolver());
        }
        return response;
    }
}