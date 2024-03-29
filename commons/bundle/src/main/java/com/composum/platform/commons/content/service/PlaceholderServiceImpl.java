/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.platform.commons.content.service;

import com.composum.platform.commons.osgi.ServiceManager;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.util.ValueEmbeddingReader;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 *
 */
@Component(
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Placeholder Service"
        }
)
public class PlaceholderServiceImpl extends ServiceManager<PlaceholderService.ValueProvider>
        implements PlaceholderService {

    private static final Logger LOG = LoggerFactory.getLogger(PlaceholderServiceImpl.class);

    public static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("(\\$\\{([^}]+)})");

    protected static final Object NULL = "";

    @Reference(
            service = ValueProvider.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE
    )
    protected void bindValueProvider(@NotNull final ServiceReference<ValueProvider> serviceReference) {
        bindReference(serviceReference);
    }

    protected void unbindValueProvider(@NotNull final ServiceReference<ValueProvider> serviceReference) {
        unbindReference(serviceReference);
    }

    @Activate
    protected void activate(final BundleContext bundleContext) {
        super.activate(bundleContext);
    }

    protected <T> T getProviderValue(@NotNull final BeanContext context,
                                     @NotNull final String key, @NotNull final Class<T> type) {
        T value;
        for (ManagedReference reference : references) {
            if ((value = reference.getService().getValue(context, key, type)) != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * a caching value map (i don't know how expensive the retrieval by the providers is)
     */
    protected class ProviderValueMap extends ValueMapDecorator {

        protected final BeanContext context;
        protected final Map<String, Object> cache;

        public ProviderValueMap(@NotNull final BeanContext context, Map<String, Object> base) {
            super(base);
            this.context = context;
            cache = new HashMap<>();
        }

        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(@NotNull final String name, @NotNull final Class<T> type) {
            T value = (T) cache.get(name);
            if (value == null) {
                value = super.get(name, type);
                if (value == null) {
                    value = getProviderValue(context, name, type);
                }
                cache.put(name, value != null ? value : NULL); // cache 'null' also
            }
            return value == NULL ? null : value;
        }

        @NotNull
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(@NotNull final String name, @NotNull final T defaultValue) {
            Class<?> type = defaultValue.getClass();
            T value = get(name, (Class<T>) type);
            return value != null ? value : defaultValue;
        }
    }

    @Override
    @NotNull
    public Map<String, Object> getValues(@NotNull final BeanContext context, @Nullable final Map<String, Object> base) {
        return new ProviderValueMap(context, base != null ? base : Collections.emptyMap());
    }

    @Override
    @NotNull
    public String applyPlaceholders(@NotNull final BeanContext context,
                                    @NotNull final String text, @NotNull final Map<String, Object> values) {
        try (StringWriter writer = new StringWriter()) {
            applyPlaceholders(context, writer, text, values);
            return writer.toString();
        } catch (IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return "";
        }
    }

    @Override
    public void applyPlaceholders(@NotNull final BeanContext context, @NotNull final Writer writer,
                                  @NotNull final String text, @NotNull final Map<String, Object> values)
            throws IOException {
        try (Reader textReader = getEmbeddingReader(context, new StringReader(text), values)) {
            IOUtils.copy(textReader, writer);
        }
    }

    /**
     * @return the appropriate value embedding reader
     * @see com.composum.sling.core.util.ValueEmbeddingReader
     */
    @Override
    @NotNull
    public Reader getEmbeddingReader(@NotNull final BeanContext context,
                                     @NotNull final Reader reader, @NotNull final Map<String, Object> values) {
        return new ValueEmbeddingReader(reader, getValues(context, values), context.getLocale(), getClass());
    }
}
