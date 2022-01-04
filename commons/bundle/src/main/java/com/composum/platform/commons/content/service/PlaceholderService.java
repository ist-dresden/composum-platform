/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.platform.commons.content.service;

import com.composum.sling.core.BeanContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;

/**
 * A service to embed dynamic values specified in a text (property) as placeholders ('${...}').
 * The placeholders can have several forms as documented in {@link com.composum.sling.core.util.ValueEmbeddingReader}.
 *
 * @see com.composum.sling.core.util.ValueEmbeddingReader
 */
public interface PlaceholderService {

    /**
     * a service interface to register placeholder value providers
     */
    interface ValueProvider {

        /**
         * @return the value of the key if the provider can retrieve it otherwise 'null'
         */
        @Nullable <T> T getValue(@NotNull BeanContext context, @NotNull String key, Class<T> type);
    }

    /**
     * @return the dynamic map of all provided values
     */
    @NotNull
    Map<String, Object> getValues(@NotNull BeanContext context, @Nullable Map<String, Object> base);

    /**
     * @return the text value with embedded placeholder values
     */
    @NotNull
    String applyPlaceholders(@NotNull BeanContext context,
                             @NotNull String text, @NotNull Map<String, Object> values);

    /**
     * writes the text to the writer with replacing of all placeholders in the text
     */
    void applyPlaceholders(@NotNull BeanContext context, @NotNull Writer writer,
                           @NotNull String text, @NotNull Map<String, Object> values)
            throws IOException;

    /**
     * @return a reader which is replacing all placeholders during read
     */
    @NotNull
    Reader getEmbeddingReader(@NotNull BeanContext context,
                              @NotNull Reader reader, @NotNull Map<String, Object> values);
}
