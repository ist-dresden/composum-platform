package com.composum.platform.models;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.Optional;
import org.apache.sling.models.annotations.Via;
import org.apache.sling.models.annotations.injectorspecific.ChildResource;
import org.apache.sling.models.annotations.injectorspecific.Self;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.apache.sling.models.factory.ModelFactory;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;

import javax.inject.Inject;
import javax.inject.Named;

import static com.composum.sling.core.util.ResourceUtil.*;
import static org.apache.sling.testing.mock.sling.ResourceResolverType.RESOURCERESOLVER_MOCK;
import static org.junit.Assert.*;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Check out Sling Models.
 *
 * @author Hans-Peter Stoerr
 */
public class SlingModelsFeaturesTest {

    private static final Logger LOG = getLogger(SlingModelsFeaturesTest.class);

    @Rule
    public final SlingContext context = new SlingContext(RESOURCERESOLVER_MOCK);

    private Resource resource;

    @Before
    public void setup() {
        ResourceBuilder resourceBuilder = context.build().resource("/something", PROP_PRIMARY_TYPE, "cpp:Page",
                "title", "hallo", PROP_TITLE, "huhu").commit();
        resource = resourceBuilder.getCurrentParent();
        Resource cfgRes = resourceBuilder.resource(CONTENT_NODE, PROP_PRIMARY_TYPE, "cpp:PageContent")
                .resource("config", "avalue", "21").commit().getCurrentParent();
        LOG.debug("Paths: {} , {}", resource.getPath(), cfgRes.getPath());
        context.addModelsForPackage(getClass().getPackage().getName());
        context.request().setResource(resource);
    }

    @Test
    public void testModel() {
        ASlingModel model = resource.adaptTo(ASlingModel.class);
        assertEquals("hallo", model.getTitle());
        assertEquals("hallo", model.titleAlias);
        assertEquals(17, model.theInt);
        assertEquals(21, model.avalue);
        assertEquals(resource.getPath(), model.resource.getPath());
        assertNotNull(model.cfgResource);

        assertEquals("hallo", resource.adaptTo(FromRequestWithResourceInterface.ASlingModelInterface.class).getTitle());
        ModelFactory modelFactory = context.getService(ModelFactory.class);
        assertEquals("hallo", modelFactory.createModel(resource, ASlingModel.class).title);

        ConstructorizedSlingModel cmodel = resource.adaptTo(ConstructorizedSlingModel.class);
        assertEquals(resource.getPath(), cmodel.resource.getPath());
    }

    @Test
    public void adaptTo() {
        assertNull(context.request().adaptTo(Resource.class));
        assertNull(context.request().adaptTo(ResourceResolver.class));
        assertNotNull(resource.adaptTo(ValueMap.class));
        assertNull(context.request().adaptTo(ValueMap.class));
        assertNull(resource.adaptTo(ResourceResolver.class));
        assertNull(context.request().adaptTo(SlingHttpServletRequest.class));
        assertNull(resource.adaptTo(Resource.class));

        assertEquals(Integer.valueOf(21),
                resource.adaptTo(ValueMap.class).get("jcr:content/config/avalue", Integer.class));
    }

    @Test
    public void fromRequest() {
        FromRequestWithResourceInterface model = context.request().adaptTo(FromRequestWithResourceInterface.class);
        assertNotNull(model);
        assertNotNull(model.props);
        assertEquals("hallo", model.props.getTitle());
        assertNotNull("hallo", model.title);
    }

    /** Something to try out Sling Models. */
    @Model(adaptables = Resource.class)
    public static class ASlingModel {

        @Inject
        private String title;

        @Inject
        @Named("title")
        protected String titleAlias;

        @Inject
        @Optional
        protected String optional;

        @Inject
        @Via(value = "jcr:content/config", type = org.apache.sling.models.annotations.via.ChildResource.class)
        protected int avalue;

        @ChildResource(name = "jcr:content/config")
        protected Resource cfgResource;

        @Inject
        @Named("jcr:content/config/avalue")
        protected Resource avalueResource;

        @Inject
        @Named("jcr:content/config/avalue")
        protected int avalueInt;

        @Inject
        @Default(intValues = 17)
        protected int theInt;

        @Self
        protected Resource resource;

        @Self
        protected ValueMap valueMap;

        @Inject
        public String getTitle() {
            return title;
        }

    }

    @Model(adaptables = Resource.class)
    protected static class ConstructorizedSlingModel {

        private final Resource resource;

        public ConstructorizedSlingModel(Resource resource) {
            this.resource = resource;
        }
    }

    @Model(adaptables = SlingHttpServletRequest.class)
    public static class FromRequestWithResourceInterface {

        // ValueMapValue works through request, too.
        @ValueMapValue
        private String title;

        @Via("resource")
        @Self
        protected ASlingModelInterface props;

        public ASlingModelInterface getProps() {
            return props;
        }

        @Model(adaptables = Resource.class)
        protected interface ASlingModelInterface {
            @Inject
            String getTitle();
        }

    }

}
