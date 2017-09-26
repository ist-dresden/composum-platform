package com.composum.platform.models.htl;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.SlingBean;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.commons.classloader.DynamicClassLoaderManager;
import org.apache.sling.models.factory.ModelFactory;
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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * HTL {@link UseProvider} which will instantiate a referenced Sling Model from a {@link
 * com.composum.sling.core.BeanContext}. This supplements the
 * {@link org.apache.sling.scripting.sightly.models.impl.SlingModelsUseProvider}
 * for use with Composum models. It has to have a higher service ranking, because SlingModelsProvider throws when the
 * object is a model but cannot be adapted from request or resource.
 *
 * @author Hans-Peter Stoerr
 * @see org.apache.sling.scripting.sightly.models.impl.SlingModelsUseProvider
 */
@Component(
        service = UseProvider.class,
        configurationPid = "com.composum.platform.models.htl.ComposumModelsUseProvider",
        property = {
                Constants.SERVICE_RANKING + ":Integer=96"
        }
)
public class ComposumModelsUseProvider implements UseProvider {

    @interface Configuration {

        @AttributeDefinition(
                name = "Service Ranking",
                description = "The Service Ranking of the ComposumModelsUseProvider. Should be minimally higher than " +
                        "SlingModelsUseProvider because this has to be run first."
        )
        int service_ranking() default 96;
    }

    private static final Logger LOG = LoggerFactory.getLogger(ComposumModelsUseProvider.class);
    private static final Pattern JAVA_PATTERN = Pattern.compile(
            "([[\\p{L}&&[^\\p{Lu}]]_$][\\p{L}\\p{N}_$]*\\.)*[\\p{Lu}_$][\\p{L}\\p{N}_$]*");


    @Reference
    private ModelFactory modelFactory;

    @Reference
    private DynamicClassLoaderManager dynamicClassLoaderManager;

    @Override
    public ProviderOutcome provide(final String identifier, final RenderContext renderContext, final Bindings
            arguments) {
        if (!JAVA_PATTERN.matcher(identifier).matches()) {
            LOG.debug("Identifier {} does not match a Java class name pattern.", identifier);
            return ProviderOutcome.failure();
        }
        final Class<?> cls;
        try {
            cls = dynamicClassLoaderManager.getDynamicClassLoader().loadClass(identifier);
        } catch (ClassNotFoundException e) {
            LOG.debug("Could not find class with the given name {}.", identifier);
            return ProviderOutcome.failure(); // try otherwise
        }
        boolean isModelClass = modelFactory.isModelClass(cls);
        boolean isSlingBean = SlingBean.class.isAssignableFrom(cls);

        if (!isModelClass && !isSlingBean) {
            LOG.debug("{} is not a Sling Model nor a SlingBean.");
            return ProviderOutcome.failure(); // try otherwise
        }

        Bindings globalBindings = renderContext.getBindings();
        BeanContext beanContext = new BeanContext.Page(new HtlPageContext(globalBindings));

        Map<String, Object> originalRequestAttributes = new HashMap<>();
        SlingHttpServletRequest request = beanContext.getRequest();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            // set args as request attributes, compatible to SlingModelsUseProvider
            originalRequestAttributes.put(entry.getKey(), request.getAttribute(entry.getKey()));
            request.setAttribute(entry.getKey(), entry.getValue());
        }

        try {
            if (isSlingBean && !isModelClass) {
                try {
                    SlingBean bean = (SlingBean) cls.newInstance();
                    bean.initialize(beanContext);
                    return ProviderOutcome.success(bean);
                } catch (RuntimeException | IllegalAccessException | InstantiationException e) {
                    LOG.error("Couldn't initialize " + cls, e);
                    return ProviderOutcome.failure(e);
                }
            }

            try {
                if (modelFactory.canCreateFromAdaptable(beanContext, cls)) {
                    LOG.debug("Trying to instantiate class {} as Sling Model from beanContext.", cls);
                    return ProviderOutcome.notNullOrFailure(modelFactory.createModel(beanContext, cls));
                }
                return ProviderOutcome.failure(); // try otherwise
            } catch (Throwable e) {
                return ProviderOutcome.failure(e);
            }
        } finally {
            for (Map.Entry<String, Object> entry : originalRequestAttributes.entrySet())
                request.setAttribute(entry.getKey(), entry.getValue());
        }
    }

}
