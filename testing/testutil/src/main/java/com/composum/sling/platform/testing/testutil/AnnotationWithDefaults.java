package com.composum.sling.platform.testing.testutil;

import com.google.common.base.Defaults;

import org.jetbrains.annotations.NotNull;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Instantiates an annotation interface so that it returns it's defaults. */
public class AnnotationWithDefaults implements InvocationHandler {

    /**
     * Creates an instance of an annotation that returns the declared defaults as values. Usage e.g.
     * <code>@interface Something{...};
     * Something something = AnnotationWithDefaults.of(Something.class);
     * </code>
     *
     * @param annotation the class for the annotation
     * @return an instance of the annotation
     */
    @NotNull
    public static <A extends Annotation> A of(@NotNull Class<A> annotation) {
        return annotation.cast(
                Proxy.newProxyInstance(annotation.getClassLoader(), new Class[]{annotation}, new AnnotationWithDefaults()));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        return method.getDefaultValue() != null ? method.getDefaultValue() :
                Defaults.defaultValue(method.getReturnType());
    }

}
