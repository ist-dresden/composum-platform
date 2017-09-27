package com.composum.platform.models.adapter;

import com.composum.platform.models.annotations.*;
import com.composum.sling.core.BeanContext;
import com.composum.sling.core.ResourceHandle;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Default;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.Optional;
import org.apache.sling.models.annotations.Via;
import org.apache.sling.models.annotations.injectorspecific.Self;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;

import java.util.Locale;

import static com.composum.sling.core.util.ResourceUtil.CONTENT_NODE;
import static com.composum.sling.core.util.ResourceUtil.PROP_PRIMARY_TYPE;
import static org.apache.sling.models.annotations.injectorspecific.InjectionStrategy.OPTIONAL;
import static org.junit.Assert.*;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Tests for {@link PropertyInjector}.
 *
 * @author Hans-Peter Stoerr
 */
public class PropertyInjectorTest {

    private static final Logger LOG = getLogger(PropertyInjectorTest.class);

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    protected Resource resource;
    protected BeanContext beanContext;

    @Before
    public void setUp() throws Exception {
        context.registerInjectActivateService(new PropertyInjector());
        context.registerInjectActivateService(new DescendantPathViaProvider());
        context.addModelsForPackage(getClass().getPackage().getName());

        ResourceBuilder parentBuilder = context.build().resource("/super", PROP_PRIMARY_TYPE, "cpp:Site");
        parentBuilder.resource(CONTENT_NODE, PROP_PRIMARY_TYPE, "cpp:SiteConfiguration")
                .resource("config/test", "inheritedvalue", "99");
        ResourceBuilder resourceBuilder = parentBuilder.resource("something", PROP_PRIMARY_TYPE, "cpp:Page")
                .resource(CONTENT_NODE, PROP_PRIMARY_TYPE, "cpp:PageContent", "title", "hallo");
        resource = resourceBuilder.getCurrentParent();
        resourceBuilder.resource("config", "i18nizedcfg", "cfgdefault");
        resourceBuilder.resource("config/i18n/de", "i18nizedcfg", "cfgde");
        resourceBuilder.resource("config/i18n/en_GB", "i18nizedcfg", "cfgen");

        resourceBuilder.resource("config/test", "avalue", "21");
        resourceBuilder.resource("i18n/de_DE", "i18nized", "germanized");
        resourceBuilder.resource("i18n/en", "i18nized", "englishized");
        resourceBuilder.commit();

        context.request().setResource(resource);
        context.request().setLocale(Locale.UK);
        beanContext = new BeanContext.Servlet(null, context.bundleContext(),
                context.request(), context.response());
        beanContext.setAttribute(BeanContext.ATTR_LOCALE, Locale.GERMANY, BeanContext.Scope.request);
        assertEquals(Locale.GERMANY, beanContext.getLocale());

        ResourceHandle handle = ResourceHandle.use(resource); // check behaviour of inherited
        assertEquals(Integer.valueOf(21), handle.getProperty("config/test/avalue", Integer.class));
        assertEquals(Integer.valueOf(21), handle.getInherited("config/test/avalue", Integer.class));
        assertEquals(null, handle.getProperty("config/test/inheritedvalue", Integer.class));
        assertEquals(Integer.valueOf(99), handle.getInherited("config/test/inheritedvalue", Integer.class));
    }

    private TestingPropertyAtPath verify(TestingPropertyAtPath model) {
        assertNotNull(model);
        assertEquals(Integer.valueOf(21), model.avalueAlias);
        assertEquals("hallo", model.title);
        assertEquals(21, model.avalue);
        assertEquals(99, model.inheritedvalue);
        assertEquals(7, model.thedefault);
        assertNull(model.doesntexist);
        assertEquals(21, model.props.avalue());
        assertEquals(21, model.propsDetresource.avalue());
        return model;
    }

    @Model(adaptables = {Resource.class, SlingHttpServletRequest.class, BeanContext.class})
    @PropertyDefaults(i18nStrategy = InternationalizationStrategy.I18NFOLDER.class)
    public static class TestingPropertyAtPath {

        @Property
        private String title;

        @Property(basePath = "config/test")
        private int avalue;

        @Property(name = "test/avalue", basePath = "config", inherited = true)
        private Integer avalueAlias;

        @Property(name = "config/test/inheritedvalue", inherited = true)
        private int inheritedvalue;

        @Property
        @Default(intValues = 7)
        private int thedefault;

        @Property(i18n = true)
        @Optional
        private String i18nized;

        @Property(injectionStrategy = OPTIONAL)
        private Boolean doesntexist;

        @Self
        @Via(value = "config", type = DescendantPath.class)
        MyProps props;

        @Model(adaptables = {Resource.class, SlingHttpServletRequest.class, BeanContext.class})
        private interface MyProps {

            @Property(name = "test/avalue")
            int avalue();

            @Property(i18n = true)
            @Optional
            String i18nizedcfg();
        }

        @Self
        MyPropsWithDetermineResource propsDetresource;

        @Model(adaptables = {Resource.class, SlingHttpServletRequest.class, BeanContext.class})
        @PropertyDetermineResourceStrategy(GetConfig.class)
        private interface MyPropsWithDetermineResource{
            @Property(name = "test/avalue")
            int avalue();

            @Property(i18n = true)
            @Optional
            String i18nizedcfg();
        }

    }

    protected static class GetConfig implements DetermineResourceStategy {
        @Override
        public Resource determineResource(BeanContext beanContext, Resource requestResource) {
            return requestResource.getChild("config");
        }
    }

    @Test
    public void resource() {
        TestingPropertyAtPath model = verify(resource.adaptTo(TestingPropertyAtPath.class));
        assertNull(model.i18nized);
        assertEquals("cfgdefault", model.props.i18nizedcfg());
        assertEquals("cfgdefault", model.propsDetresource.i18nizedcfg());
    }

    @Test
    public void request() {
        TestingPropertyAtPath model = verify(context.request().adaptTo(TestingPropertyAtPath.class));
        assertEquals("englishized", model.i18nized);
        assertEquals("cfgen", model.props.i18nizedcfg());
        assertEquals("cfgen", model.propsDetresource.i18nizedcfg());
    }

    @Test
    public void beanContext() {
        TestingPropertyAtPath model = verify(beanContext.adaptTo(TestingPropertyAtPath.class));
        assertEquals("germanized", model.i18nized);
        assertEquals("cfgde", model.props.i18nizedcfg());
        assertEquals("cfgde", model.propsDetresource.i18nizedcfg());
    }

}
