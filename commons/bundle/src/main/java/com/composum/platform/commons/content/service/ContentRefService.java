package com.composum.platform.commons.content.service;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;

public interface ContentRefService {

    String CODE_ENCODING = "UTF-8";

    String getReferencedContent(ResourceResolver resolver, String path);

    String getRenderedContent(SlingHttpServletRequest contextRequest, String url, boolean emptyLines);
}
