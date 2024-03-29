package com.composum.platform.commons.content.service;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.awt.image.BufferedImage;

/**
 * a service to provide access to the content of referenced resources
 */
public interface ContentRefService {

    String CODE_ENCODING = "UTF-8";

    /**
     * returns the content of a referenced file resource as string
     * (e.g. to embed the source code during component rendering)
     */
    @NotNull
    String getReferencedContent(@NotNull ResourceResolver resolver, String path);

    /**
     * returns the result of the rendering of a resource by sending an internal request;
     * returns an empty string if the request fails
     *
     * @param contextRequest the received request
     * @param url            the url to the referenced resource;
     *                       the url is used to determine the resource path ans simulate a request to a real URL
     * @param emptyLines     remove multiple empty lines if this is not 'true'
     */
    @NotNull
    String getRenderedContent(@NotNull SlingHttpServletRequest contextRequest, String url, boolean emptyLines);

    @Nullable
    BufferedImage getRenderedImage(@NotNull final SlingHttpServletRequest contextRequest,
                                   @NotNull final String url, int width, @Nullable Integer height,
                                   @Nullable Double scale);
}
