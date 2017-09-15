package com.composum.platform.models.annotations;


import com.composum.sling.core.InheritedValues;
import org.apache.sling.models.annotations.Source;
import org.apache.sling.models.annotations.injectorspecific.InjectionStrategy;
import org.apache.sling.models.spi.injectorspecific.InjectAnnotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotation to be used on either methods, fields or constructor parameter to let Sling Models inject a value from the
 * ValueMap of a subpath of the current resource.
 *
 * @author Hans-Peter Stoerr
 */
@Target({METHOD, FIELD})
@Retention(RUNTIME)
@InjectAnnotation
@Source(Property.INJECTORNAME)
@Documented
public @interface Property {
    /**
     * Optional name (or relative path) to the value from the value map to take. If empty, then the name
     * is derived from the method or field name, or the @{@link javax.inject.Named} annotation.
     */
    String name() default "";

    /** Optional basic path that is put before the name. Could be used if you have several resources that are named
     * like the variables they are injected into, but have a common path prefix. */
    String basePath() default "";

    /** If true, inherited attributes should be considered, too. */
    boolean inherited() default false;

    InheritedValues.Type inheritanceType() default InheritedValues.Type.useDefault;

    /**
     * if set to REQUIRED injection is mandatory, if set to OPTIONAL injection is optional, in case of DEFAULT the
     * standard annotations ({@link org.apache.sling.models.annotations.Optional}, {@link
     * org.apache.sling.models.annotations.Required}) are used. If even those are not available the default injection
     * strategy defined on the {@link org.apache.sling.models.annotations.Model} applies. Default value = DEFAULT.
     */
    InjectionStrategy injectionStrategy() default InjectionStrategy.DEFAULT;

    /**
     * If set, then the child resource can be obtained via a projection of the given property of the adaptable.
     */
    String via() default "";

    /**
     * If true, the {@link #i18nStrategy()} (or the default {@link PropertyDefaults#i18nStrategy()} is used to retrieve
     * the value.
     */
    boolean i18n() default false;

    /**
     * The internationalization strategy to be used when retrieving internationalized attributes with {@link
     * #i18n()}=true. The default is taken from @{@link PropertyDefaults} of the annotated class.
     */
    Class<? extends InternationalizationStrategy> i18nStrategy()
            default InternationalizationStrategy.USEDEFAULT.class;

    /** Strategy to determine the resource the properties of the model should be initialized from. */
    Class<? extends DetermineResourceStategy> determineResourceStrategy()
            default DetermineResourceStategy.DefaultDetermineResourceStrategy.class;

    /** Name of the injector that works on this. */
    public static final String INJECTORNAME = "valuemap-at-path";
}
