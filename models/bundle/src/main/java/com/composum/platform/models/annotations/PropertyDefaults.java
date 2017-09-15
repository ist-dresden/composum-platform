package com.composum.platform.models.annotations;

import com.composum.sling.core.InheritedValues;

import java.lang.annotation.*;

/**
 * <p>Defines some defaults that apply to a {@link org.apache.sling.models.annotations.Model} class, mostly used in
 * conjunction with {@link Property}.</p>
 * <p>
 * <p>CAUTION: this specifies the defaults only for the fields/methods/constructors in the subclasses. If you specify
 * this on a class and again on a subclass, the subclass's defaults will not change the behaviour for stuff defined in
 * its superclasses.</p>
 *
 * @author Hans-Peter Stoerr
 * @see Property
 * @since 09/2017
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface PropertyDefaults {

    /** Default for {@link Property#inheritanceType()} for fields/methods/constructors defined in subclasses. */
    InheritedValues.Type inheritanceType() default InheritedValues.Type.contentRelated;

    /** Default for {@link Property#i18nStrategy()} for fields/methods/constructors defined in subclasses. */
    Class<? extends InternationalizationStrategy> i18nStrategy() default InternationalizationStrategy.NONE.class;

    Class<? extends DetermineResourceStategy> determineResourceStrategy()
            default DetermineResourceStategy.OriginalResourceStrategy.class;

}
