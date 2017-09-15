package com.composum.platform.models.adapter;

import com.composum.sling.core.BeanContext;
import com.composum.sling.core.ResourceHandle;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;

import static com.composum.sling.core.util.ResourceUtil.CONTENT_NODE;
import static com.composum.sling.core.util.ResourceUtil.PROP_PRIMARY_TYPE;
import static org.apache.sling.models.annotations.injectorspecific.InjectionStrategy.OPTIONAL;
import static org.junit.Assert.*;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Tests for {@link PropertyAtPathInjector}.
 *
 * @author Hans-Peter Stoerr
 */
public class PropertyAtPathInjectorTest {

    private static final Logger LOG = getLogger(PropertyAtPathInjectorTest.class);

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    protected Resource resource;
    protected BeanContext beanContext;

    @Before
    public void setUp() throws Exception {
        context.registerInjectActivateService(new PropertyAtPathInjector());
        context.addModelsForPackage(getClass().getPackage().getName());

        ResourceBuilder parentBuilder = context.build().resource("/super", PROP_PRIMARY_TYPE, "cpp:Site");
        parentBuilder.resource(CONTENT_NODE, PROP_PRIMARY_TYPE, "cpp:SiteConfiguration")
                .resource("config/test", "inheritedvalue", "99");
        ResourceBuilder resourceBuilder = parentBuilder.resource("something", PROP_PRIMARY_TYPE, "cpp:Page")
                .resource(CONTENT_NODE, PROP_PRIMARY_TYPE, "cpp:PageContent", "title", "hallo");
        resource = resourceBuilder.getCurrentParent();
        resourceBuilder.resource("config/test", "avalue", "21").commit();

        context.request().setResource(resource);
        beanContext = new BeanContext.Servlet(null, context.bundleContext(), context.request(), context.response());

        ResourceHandle handle = ResourceHandle.use(resource);
        assertEquals(Integer.valueOf(21), handle.getProperty("config/test/avalue", Integer.class));
        assertEquals(Integer.valueOf(21), handle.getInherited("config/test/avalue", Integer.class));
        assertEquals(null, handle.getProperty("config/test/inheritedvalue", Integer.class));
        assertEquals(Integer.valueOf(99), handle.getInherited("config/test/inheritedvalue", Integer.class));
    }

    private void verify(TestingPropertyAtPath model) {
        assertNotNull(model);
        assertEquals(Integer.valueOf(21), model.avalueAlias);
        assertEquals("hallo", model.title);
        assertEquals(21, model.avalue);
        assertEquals(99, model.inheritedvalue);
    }

    @Model(adaptables = {Resource.class, SlingHttpServletRequest.class, BeanContext.class})
    public static class TestingPropertyAtPath {
        @PropertyAtPath(path = "")
        private String title;

        @PropertyAtPath(path = "config/test")
        private int avalue;

        @PropertyAtPath(path = "config/test", name = "avalue", inherited = true)
        private Integer avalueAlias;

        @PropertyAtPath(path = "config/test", inherited = true, injectionStrategy = OPTIONAL)
        private int inheritedvalue;
    }

    @Test
    public void resource() {
        verify(resource.adaptTo(TestingPropertyAtPath.class));
    }

    @Test
    public void request() {
        verify(context.request().adaptTo(TestingPropertyAtPath.class));
    }

    @Test
    public void beanContext() {
        verify(beanContext.adaptTo(TestingPropertyAtPath.class));
    }

}
