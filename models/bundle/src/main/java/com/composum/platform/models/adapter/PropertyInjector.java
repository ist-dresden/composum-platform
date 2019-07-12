package com.composum.platform.models.adapter;


import com.composum.platform.models.annotations.DetermineResourceStategy;
import com.composum.platform.models.annotations.InternationalizationStrategy;
import com.composum.platform.models.annotations.Property;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.ResourceHandle;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.adapter.Adaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.apache.sling.models.spi.AcceptsNullName;
import org.apache.sling.models.spi.DisposalCallbackRegistry;
import org.apache.sling.models.spi.Injector;
import org.apache.sling.models.spi.ValuePreparer;
import org.apache.sling.models.spi.injectorspecific.AbstractInjectAnnotationProcessor2;
import org.apache.sling.models.spi.injectorspecific.InjectAnnotationProcessor2;
import org.apache.sling.models.spi.injectorspecific.StaticInjectAnnotationProcessorFactory;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Injector for {@link Property}.
 *
 * @author Hans-Peter Stoerr
 */
@Component(
        property = {
                Constants.SERVICE_DESCRIPTION + "=Sling-Models extension to inject attributes from resource descendants, possibly inherited.",
                Constants.SERVICE_RANKING + ":Integer=1500"
        },
        service = {Injector.class, StaticInjectAnnotationProcessorFactory.class},
        immediate = true
)
public class PropertyInjector implements Injector, StaticInjectAnnotationProcessorFactory, ValuePreparer,
        AcceptsNullName {

    private static final Logger LOG = getLogger(PropertyInjector.class);

    @Override
    public @Nonnull
    String getName() {
        return Property.INJECTORNAME;
    }

    @Override
    public Object getValue(@Nonnull Object adaptable, String name, @Nonnull Type type,
                           @Nonnull AnnotatedElement element, @Nonnull DisposalCallbackRegistry callbackRegistry) {

        PropertyJoinedWithDefaultsWrapper annotation = PropertyJoinedWithDefaultsWrapper.getWithDefaults(element);
        if (null == annotation) return null;
        PreparedValues preparedValues = PreparedValues.make(adaptable);
        ResourceHandle handle = preparedValues.determineResource(annotation.value());
        if (null == handle || !handle.isValid()) return null;

        String attribute = defaultIfBlank(annotation.name(), name);
        if (isNotBlank(annotation.basePath())) attribute = annotation.basePath() + '/' + attribute;

        if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>) type;
            try {
                InternationalizationStrategy i18nStrategy = annotation.i18nStrategy().newInstance();
                return i18nStrategy.getInternationalized(handle, attribute, clazz, preparedValues.getBeanContext(),
                        preparedValues.request,
                        preparedValues.locale, annotation);
            } catch (InstantiationException | IllegalAccessException e) {
                LOG.error("Can't instantiate " + annotation.i18nStrategy(), e);
                return null; // somewhat doubtable, but the general convention in sling-models
            }
        } else {
            LOG.debug("PropertyInjector doesn't support non-class types {}", type);
            return null;
        }
    }

    @Override
    public Object prepareValue(final Object adaptable) {
        PreparedValues res = PreparedValues.make(adaptable);
        return null != res ? res : ObjectUtils.NULL;
    }

    protected static class PreparedValues {
        protected BeanContext beanContext;
        protected SlingHttpServletRequest request;
        protected Locale locale;
        protected Resource resource;
        protected Map<Class<?>, ResourceHandle> determinedResources = new HashMap<>();

        @CheckForNull
        public static PreparedValues make(Object adaptable) {
            if (null == adaptable || ObjectUtils.NULL.equals(adaptable)) return null;
            if (adaptable instanceof PreparedValues) return (PreparedValues) adaptable;
            PreparedValues res = new PreparedValues();
            if (adaptable instanceof Resource) res.resource = (Resource) adaptable;
            else if (adaptable instanceof SlingHttpServletRequest) {
                res.request = ((SlingHttpServletRequest) adaptable);
                res.resource = res.request.getResource();
                res.locale = res.request.getLocale();
            } else if (adaptable instanceof Adaptable) { // for instance BeanContext
                Adaptable a = (Adaptable) adaptable;
                res.request = a.adaptTo(SlingHttpServletRequest.class);
                res.resource = a.adaptTo(Resource.class);
                res.locale = a.adaptTo(Locale.class);
                res.beanContext = a.adaptTo(BeanContext.class);
                if (null == res.locale && null != res.request) res.locale = res.request.getLocale();
            } else return null;
            return res;
        }

        public ResourceHandle determineResource(@Nonnull Class<? extends DetermineResourceStategy> strategy) {
            ResourceHandle res = determinedResources.get(strategy);
            if (null == res) {
                if (null == strategy || DetermineResourceStategy.OriginalResourceStrategy.class == strategy) {
                    res = ResourceHandle.use(resource);
                } else try {
                    res = ResourceHandle.use(strategy.newInstance().determineResource(beanContext, resource));
                } catch (InstantiationException | IllegalAccessException e) {
                    LOG.error("Can't instantiate " + strategy, e);
                    return null; // somewhat doubtable, but the general convention in sling-models
                }
                determinedResources.put(strategy, res);
            }
            return res;
        }

        public BeanContext getBeanContext() {
            return beanContext;
        }
    }

    @Override
    public InjectAnnotationProcessor2 createAnnotationProcessor(AnnotatedElement element) {
        Property annotation = element.getAnnotation(Property.class);
        if (annotation != null) {
            return new PropertyAtPathAnnotationProcessor(annotation);
        }
        return null;
    }

    protected static class PropertyAtPathAnnotationProcessor extends AbstractInjectAnnotationProcessor2 {

        private final Property annotation;

        public PropertyAtPathAnnotationProcessor(Property annotation) {
            this.annotation = annotation;
        }

        @Override
        public String getName() {
            return isNotBlank(annotation.name()) ? annotation.name() : null;
        }

        @Override
        public String getVia() {
            return isNotBlank(annotation.via()) ? annotation.via() : null;
        }

        @Override
        public InjectionStrategy getInjectionStrategy() {
            return annotation.injectionStrategy();
        }
    }

}
