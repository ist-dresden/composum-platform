package com.composum.platform.commons.content.service;

import org.apache.sling.api.SlingHttpServletRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.Reader;

/**
 * an optional service which provides the transformation of a rendered HTML snipped into an image
 */
public interface HtmlImageRenderer {

    @Nonnull
    BufferedImage htmlToImage(@Nonnull SlingHttpServletRequest contextRequest,
                              @Nonnull String contextUrl, @Nonnull Reader htmlSnippet,
                              int width, @Nullable final Integer height,
                              @Nullable final Double scale, @Nonnull Color background)
            throws Exception;
}
