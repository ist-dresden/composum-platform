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

import javax.servlet.ServletException;
import java.io.IOException;

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
    public String getString(SlingHttpServletRequest contextRequest, PathInfo pathInfo)
            throws ServletException, IOException {
        MockSlingHttpServletResponse response = doGet(contextRequest, pathInfo);
        return response.getOutputAsString();
    }

    @Override
    public byte[] getBytes(SlingHttpServletRequest contextRequest, PathInfo pathInfo)
            throws ServletException, IOException {
        MockSlingHttpServletResponse response = doGet(contextRequest, pathInfo);
        return response.getOutput();
    }

    protected SlingResponse doGet(SlingHttpServletRequest contextRequest, PathInfo pathInfo)
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
