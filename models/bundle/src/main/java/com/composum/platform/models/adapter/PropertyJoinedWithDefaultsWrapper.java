package com.composum.platform.models.adapter;

import com.composum.platform.models.annotations.*;
import com.composum.sling.core.InheritedValues;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Wrapper for {@link Property} annotation that supplements it with the defaults from the {@link PropertyDefaults}
 * annotation.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
@SuppressWarnings("ClassExplicitlyAnnotation")
class PropertyJoinedWithDefaultsWrapper implements Property, PropertyDetermineResourceStrategy {

    private static final Logger LOG = getLogger(PropertyJoinedWithDefaultsWrapper.class);
    protected static PropertyDefaults defaultForPropertyDefaults =
            Defaulted.class.getAnnotation(PropertyDefaults.class);
    protected static Property defaultForProperty;

    static {
        try {
            defaultForProperty = Defaulted.class.getDeclaredMethod("getDefault").getAnnotation(Property.class);
        } catch (NoSuchMethodException e) {
            LOG.error("Impossible: " + e, e);
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Property propertyAnnotation;
    private final PropertyDefaults propertyDefaultsAnnotation;
    private final PropertyDetermineResourceStrategy determineResourceStrategy;

    protected PropertyJoinedWithDefaultsWrapper(Property propertyAnnotation, PropertyDefaults
            propertyDefaultsAnnotation, PropertyDetermineResourceStrategy determineResourceStrategy) {
        this.propertyAnnotation = propertyAnnotation;
        this.propertyDefaultsAnnotation = null != propertyDefaultsAnnotation ? propertyDefaultsAnnotation :
                defaultForPropertyDefaults;
        this.determineResourceStrategy = determineResourceStrategy;
    }

    /**
     * Returns a wrapper that is instance of the {@link Property} annotation that supplements values with the defaults
     * of a {@link PropertyDefaults} annotation found at the class where element is declared or its superclasses, as
     * well as an instance of {@link PropertyDetermineResourceStrategy} found similarly.
     *
     * @param element the element
     * @return the with defaults, or null if there is no {@link Property} annotation
     */
    public static PropertyJoinedWithDefaultsWrapper getWithDefaults(@Nonnull AnnotatedElement element) {
        Property propertyAnnotation = element.getAnnotation(Property.class);
        if (null == propertyAnnotation) return null;
        Class<?> declaringClass = null;
        if (element instanceof Member) { // this doesn't work with parameters, so we can't allow @Property on those.
            declaringClass = ((Member) element).getDeclaringClass();
        } else {
            LOG.warn("Bug: Couldn't determine declaring class for {}, skipping PropertyDefaults.", element.getClass());
        }
        PropertyDefaults propertyDefaults = null;
        PropertyDetermineResourceStrategy determineResourceStrategy = null;
        while (null != declaringClass) { // go to parents in case of inner classes
            if (null == propertyDefaults) propertyDefaults = declaringClass.getAnnotation(PropertyDefaults.class);
            if (null == determineResourceStrategy) determineResourceStrategy =
                    declaringClass.getAnnotation(PropertyDetermineResourceStrategy.class);
            declaringClass = declaringClass.getDeclaringClass();
        }
        return new PropertyJoinedWithDefaultsWrapper(propertyAnnotation, propertyDefaults, determineResourceStrategy);
    }

    @Override
    public String name() {
        return propertyAnnotation.name();
    }

    @Override
    public String basePath() {
        return propertyAnnotation.basePath();
    }

    @Override
    public boolean inherited() {
        return propertyAnnotation.inherited();
    }

    @Override
    public InheritedValues.Type inheritanceType() {
        return propertyAnnotation.inheritanceType() != defaultForProperty.inheritanceType() ?
                propertyAnnotation.inheritanceType() : propertyDefaultsAnnotation.inheritanceType();
    }

    @Override
    public InjectionStrategy injectionStrategy() {
        return propertyAnnotation.injectionStrategy();
    }

    @Override
    public String via() {
        return propertyAnnotation.via();
    }

    @Override
    public boolean i18n() {
        return propertyAnnotation.i18n();
    }

    @Override
    public Class<? extends InternationalizationStrategy> i18nStrategy() {
        return propertyAnnotation.i18nStrategy() != defaultForProperty.i18nStrategy() ?
                propertyAnnotation.i18nStrategy() : propertyDefaultsAnnotation.i18nStrategy();
    }

    @Override
    public Class<? extends DetermineResourceStategy> value() {
        return null != determineResourceStrategy ? determineResourceStrategy.value() :
                DetermineResourceStategy.OriginalResourceStrategy.class;
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return propertyAnnotation.annotationType();
    }

    /** Class to easily read the defaults of the annotations. */
    @PropertyDefaults
    protected interface Defaulted {
        @Property
        void getDefault();
    }
}
