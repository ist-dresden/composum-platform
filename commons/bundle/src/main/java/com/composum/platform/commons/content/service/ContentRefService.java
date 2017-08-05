package com.composum.platform.commons.content.service;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.Nonnull;

/**
 * a service to provide access to the content of referenced resources
 */
public interface ContentRefService {

    String CODE_ENCODING = "UTF-8";

    /**
     * returns the content of a referenced file resource as string
     * (e.g. to embed the source code during component rendering)
     */
    @Nonnull
    String getReferencedContent(@Nonnull ResourceResolver resolver, String path);

    /**
     * returns the result of the rendering of a resource by sending an internal request;
     * returns an empty string if the request fails
     *
     * @param contextRequest the received request
     * @param url            the url to the referenced resource;
     *                       the url is used to determine the resource path ans simulate a request to a real URL
     * @param emptyLines     remove multiple empty lines is not 'true'
     */
    @Nonnull
    String getRenderedContent(@Nonnull SlingHttpServletRequest contextRequest, String url, boolean emptyLines);
}
