/*
 * copyright (c) 2015ff IST GmbH Dresden, Germany - https://www.ist-software.com
 *
 * This software may be modified and distributed under the terms of the MIT license.
 */
package com.composum.platform.commons.content.service;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.util.ValueEmbeddingReader;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.resource.ValueMap;
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
public class PlaceholderServiceImpl implements PlaceholderService {

    private static final Logger LOG = LoggerFactory.getLogger(PlaceholderServiceImpl.class);

    public static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("(\\$\\{([^}]+)})");

    protected static final Object NULL = "";

    protected BundleContext bundleContext;

    protected List<ValueProviderReference> valueProviders = Collections.synchronizedList(new ArrayList<>());

    @Activate
    protected void activate(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    protected <T> T getProviderValue(@Nonnull final BeanContext context,
                                     @Nonnull final String key, @Nonnull final Class<T> type) {
        T value;
        for (ValueProviderReference reference : valueProviders) {
            if ((value = reference.getProvider().getValue(context, key, type)) != null) {
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

        public ProviderValueMap(@Nonnull final BeanContext context, Map<String, Object> base) {
            super(base);
            this.context = context;
            cache = new HashMap<>();
        }

        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(@Nonnull final String name, @Nonnull final Class<T> type) {
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

        @Nonnull
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(@Nonnull final String name, @Nonnull final T defaultValue) {
            Class<?> type = defaultValue.getClass();
            T value = get(name, (Class<T>) type);
            return value != null ? value : defaultValue;
        }
    }

    @Override
    @Nonnull
    public ValueMap getValueMap(@Nonnull final BeanContext context, @Nullable final Map<String, Object> base) {
        return new ProviderValueMap(context, base != null ? base : Collections.emptyMap());
    }

    @Override
    @Nonnull
    public String applyPlaceholders(@Nonnull final BeanContext context,
                                    @Nonnull final String text, @Nonnull final Map<String, Object> values) {
        try (StringWriter writer = new StringWriter()) {
            applyPlaceholders(context, writer, text, values);
            return writer.toString();
        } catch (IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return "";
        }
    }

    @Override
    public void applyPlaceholders(@Nonnull final BeanContext context, @Nonnull final Writer writer,
                                  @Nonnull final String text, @Nonnull final Map<String, Object> values)
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
    @Nonnull
    public Reader getEmbeddingReader(@Nonnull final BeanContext context,
                                     @Nonnull final Reader reader, @Nonnull final Map<String, Object> values) {
        return new ValueEmbeddingReader(reader, getValueMap(context, values), context.getLocale(), getClass());
    }

    // value provider management

    protected class ValueProviderReference implements Comparable<ValueProviderReference> {

        public final ServiceReference<ValueProvider> reference;
        public final long serviceId;
        public final int ranking;

        private transient ValueProvider provider;

        public ValueProviderReference(ServiceReference<ValueProvider> reference) {
            this.reference = reference;
            this.serviceId = (Long) reference.getProperty(Constants.SERVICE_ID);
            final Object property = reference.getProperty(Constants.SERVICE_RANKING);
            this.ranking = !(property instanceof Integer) ? 0 : (Integer) property;
        }

        public ValueProvider getProvider() {
            if (provider == null) {
                provider = bundleContext.getService(reference);
            }
            return provider;
        }

        public long getServieId() {
            return serviceId;
        }

        public int getRanking() {
            return ranking;
        }

        @Override
        public int compareTo(@Nonnull final ValueProviderReference other) {
            return Integer.compare(other.getRanking(), getRanking()); // sort descending
        }

        // Object

        @Override
        public boolean equals(Object other) {
            return other instanceof ValueProviderReference
                    && ((ValueProviderReference) other).getServieId() == getServieId();
        }

        @Override
        public int hashCode() {
            return reference.hashCode();
        }
    }

    @Reference(
            service = ValueProvider.class,
            policy = ReferencePolicy.DYNAMIC,
            cardinality = ReferenceCardinality.MULTIPLE
    )
    protected void bindValueProvider(@Nonnull final ServiceReference<ValueProvider> serviceReference) {
        final ValueProviderReference reference = new ValueProviderReference(serviceReference);
        LOG.info("bindValueProvider: {}", reference);
        valueProviders.add(reference);
        Collections.sort(valueProviders);
    }

    protected void unbindValueProvider(@Nonnull final ServiceReference<ValueProvider> serviceReference) {
        final ValueProviderReference reference = new ValueProviderReference(serviceReference);
        LOG.info("unbindValueProvider: {}", reference);
        valueProviders.remove(reference);
    }
}
