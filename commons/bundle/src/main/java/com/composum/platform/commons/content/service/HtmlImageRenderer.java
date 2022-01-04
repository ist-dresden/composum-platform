package com.composum.platform.commons.content.service;

import org.apache.sling.api.SlingHttpServletRequest;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.Reader;

/**
 * an optional service which provides the transformation of a rendered HTML snipped into an image
 */
public interface HtmlImageRenderer {

    @NotNull
    BufferedImage htmlToImage(@NotNull SlingHttpServletRequest contextRequest,
                              @NotNull String contextUrl, @NotNull Reader htmlSnippet,
                              int width, @Nullable final Integer height,
                              @Nullable final Double scale, @NotNull Color background)
            throws Exception;
}
