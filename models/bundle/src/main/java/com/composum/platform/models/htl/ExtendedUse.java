package com.composum.platform.models.htl;

import org.apache.sling.scripting.sightly.render.RenderContext;

import javax.script.Bindings;

/**
 * Allows initialization of Java objects when created by HTL with {@link ExtendedJavaUseProvider}.
 *
 * @author Hans-Peter Stoerr
 * @see org.apache.sling.scripting.sightly.pojo.Use
 * @since 09/2017
 */
public interface ExtendedUse {

    /**
     * <p> Called to initialize the Java object with the current Java Scripting API arguments. </p> <p> This method is
     * called only if the object has been instantiated by HTL as part of processing the {@code data-sly-use} attribute.
     * The Java Scripting API arguments provide all the global variables known to a script being executed. </p>
     *
     * @param renderContext the context where this is called. Caution: modifying {@link RenderContext#getBindings()}
     *                      will change the global bindings.
     * @param arguments     The Java Scripting API arguments. Caution: modifying this could interfere with other
     *                      providers.
     */
    void init(RenderContext renderContext, Bindings arguments);

}
