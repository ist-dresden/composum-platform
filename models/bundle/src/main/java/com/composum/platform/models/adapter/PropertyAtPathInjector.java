package com.composum.platform.models.adapter;


import com.composum.sling.core.ResourceHandle;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
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
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Array;
import java.lang.reflect.Type;

import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Injector for {@link PropertyAtPath}.
 *
 * @author Hans-Peter Stoerr
 */
@Component(immediate = true,
        description = "Sling-Models extension to inject attributes from resource descendants, possibly inherited.")
@Service()
@Property(name = Constants.SERVICE_RANKING, intValue = 1500)
public class PropertyAtPathInjector implements Injector, StaticInjectAnnotationProcessorFactory, ValuePreparer,
        AcceptsNullName {

    private static final Logger LOG = getLogger(PropertyAtPathInjector.class);

    @Override
    public @Nonnull
    String getName() {
        return PropertyAtPath.INJECTORNAME;
    }

    @Override
    public Object getValue(@Nonnull Object adaptable, String name, @Nonnull Type type,
                           @Nonnull AnnotatedElement element, @Nonnull DisposalCallbackRegistry callbackRegistry) {

        PropertyAtPath annotation = element.getAnnotation(PropertyAtPath.class);
        if (null == annotation) return null;

        ResourceHandle handle = getResourceHandle(adaptable);
        if (null == handle) return null;
        String attribute = defaultString(name, annotation.name());
        String path = isNotBlank(annotation.path()) ? annotation.path() + "/" + attribute : attribute;

        if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>) type;
            if (annotation.inherited()) return handle.getInherited(path, clazz);
            return handle.getProperty(path, clazz);
        } else {
            LOG.debug("PropertyAtPathInjector doesn't support non-class types {}", type);
            return null;
        }
    }

    private Object unwrapArray(Object wrapperArray, Class<?> primitiveType) {
        int length = Array.getLength(wrapperArray);
        Object primitiveArray = Array.newInstance(primitiveType, length);
        for (int i = 0; i < length; i++) {
            Array.set(primitiveArray, i, Array.get(wrapperArray, i));
        }
        return primitiveArray;
    }

    private Object wrapArray(Object primitiveArray, Class<?> wrapperType) {
        int length = Array.getLength(primitiveArray);
        Object wrapperArray = Array.newInstance(wrapperType, length);
        for (int i = 0; i < length; i++) {
            Array.set(wrapperArray, i, Array.get(primitiveArray, i));
        }
        return wrapperArray;
    }

    @Override
    public Object prepareValue(final Object adaptable) {
        ResourceHandle rh = getResourceHandle(adaptable);
        return null != rh ? rh : ObjectUtils.NULL;
    }

    protected ResourceHandle getResourceHandle(Object adaptable) {
        if (null == adaptable || ObjectUtils.NULL.equals(adaptable)) return null;
        if (adaptable instanceof ResourceHandle) return (ResourceHandle) adaptable;
        LOG.debug("Creating ResourceHandle for {}@{}", adaptable.getClass(), System.identityHashCode(adaptable));
        if (adaptable instanceof Resource) return ResourceHandle.use((Resource) adaptable);
        if (adaptable instanceof SlingHttpServletRequest) return ResourceHandle.use(((SlingHttpServletRequest)
                adaptable).getResource());
        if (adaptable instanceof Adaptable) {
            Resource resource = ((Adaptable) adaptable).adaptTo(Resource.class);
            if (null != resource) return ResourceHandle.use(resource);
        }
        return null;
    }

    @Override
    public InjectAnnotationProcessor2 createAnnotationProcessor(AnnotatedElement element) {
        PropertyAtPath annotation = element.getAnnotation(PropertyAtPath.class);
        if (annotation != null) {
            return new PropertyAtPathAnnotationProcessor(annotation);
        }
        return null;
    }

    private static class PropertyAtPathAnnotationProcessor extends AbstractInjectAnnotationProcessor2 {

        private final PropertyAtPath annotation;

        public PropertyAtPathAnnotationProcessor(PropertyAtPath annotation) {
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
