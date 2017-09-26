package com.composum.platform.models.htl;

import org.apache.sling.commons.classloader.ClassLoaderWriter;
import org.apache.sling.scripting.sightly.render.RenderContext;
import org.apache.sling.scripting.sightly.use.ProviderOutcome;
import org.apache.sling.scripting.sightly.use.UseProvider;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.script.Bindings;
import javax.script.SimpleBindings;
import java.util.regex.Pattern;

/**
 * A HTL {@link org.apache.sling.scripting.sightly.use.UseProvider} similar to the {@link
 * org.apache.sling.scripting.sightly.impl.engine.extension.use.JavaUseProvider} that allows accessing the {@link
 * RenderContext}, too - the constructed Java object has to implement {@link ExtendedUse} with {@link
 * ExtendedUse#init(RenderContext, Bindings)}.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
@Component(
        service = UseProvider.class,
        configurationPid = "com.composum.platform.models.htl.ExtendedJavaUseProvider",
        property = {
                Constants.SERVICE_RANKING + ":Integer=91"
        }
)
public class ExtendedJavaUseProvider implements UseProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ExtendedJavaUseProvider.class);

    @interface Configuration {
        @AttributeDefinition(
                name = "Service Ranking",
                description = "Priority of the ExtendedJavaUseProvider. The service ranking needs to be higher than " +
                        "org.apache.sling.scripting.sightly.impl.engine.extension.use.JavaUseProvider."
        )
        int service_ranking() default 91;
    }

    private static final Pattern JAVA_PATTERN = Pattern.compile("([[\\p{L}&&[^\\p{Lu}]]_$][\\p{L}\\p{N}_$]*\\.)" +
            "*[\\p{Lu}_$][\\p{L}\\p{N}_$]*");

    @Reference
    private ClassLoaderWriter classLoaderWriter;


    @Override
    public ProviderOutcome provide(String identifier, RenderContext renderContext, Bindings arguments) {
        if (!JAVA_PATTERN.matcher(identifier).matches()) {
            LOG.debug("Identifier {} does not match a Java class name pattern.", identifier);
            return ProviderOutcome.failure();
        }
        Bindings globalBindings = renderContext.getBindings();

        try {
            Class<?> cls = classLoaderWriter.getClassLoader().loadClass(identifier);
            if (ExtendedUse.class.isAssignableFrom(cls)) {
                ExtendedUse result = (ExtendedUse) cls.newInstance();
                result.init(renderContext, arguments);
                return ProviderOutcome.notNullOrFailure(result);
            }
            return ProviderOutcome.failure();
        } catch (ClassNotFoundException e) { // never mind - try other providers
            return ProviderOutcome.failure();
        } catch (Exception e) {
            // any other exception is an error
            return ProviderOutcome.failure(e);
        }
    }
}
