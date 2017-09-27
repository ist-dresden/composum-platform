package com.composum.platform.models.htl;

import org.apache.sling.scripting.api.BindingsValuesProvider;
import org.osgi.service.component.annotations.Component;

import javax.script.Bindings;

/**
 * A {@link org.apache.sling.scripting.api.BindingsValuesProvider} that binds the outermost bindings themselves into a
 * binding, such that global variables can be added / changed.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
@Component(
        property = {
                "javax.script.name=sightly"
        }
)
public class OuterBindingsBindingsValueProvider implements BindingsValuesProvider {

    /** A binding that references the {@link javax.script.Bindings} on the outermost level. */
    public static final String OUTER_BINDINGS = "outerBindings";


    @Override
    public void addBindings(Bindings bindings) {
        if (!bindings.containsKey(OUTER_BINDINGS)) {
            bindings.put(OUTER_BINDINGS, bindings);
        }
    }
}
