package com.composum.platform.models.adapter;


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
@Target({METHOD, FIELD, PARAMETER})
@Retention(RUNTIME)
@InjectAnnotation
@Source(PropertyAtPath.INJECTORNAME)
@Documented
public @interface PropertyAtPath {
    /**
     * Specifies the name of the value from the value map to take. If empty, then the name is derived from the method or
     * field.
     */
    String name() default "";

    /** Specifies the path to the resource from which we take the value. */
    String path() default "";

    /** If true, inherited attributes should be considered, too. */
    boolean inherited() default false;

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

    /** Name of the injector that works on this. */
    public static final String INJECTORNAME = "valuemap-at-path";
}
