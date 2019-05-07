package com.composum.platform.models.htl;

import com.composum.sling.core.util.ServiceHandle;
import com.composum.sling.core.util.XSS;
import com.composum.sling.cpnl.TextTag;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScript;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.commons.classloader.DynamicClassLoaderManager;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.apache.sling.xss.XSSFilter;
import org.apache.sling.xss.impl.XSSAPIImpl;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;
import org.reflections.ReflectionUtils;
import org.slf4j.Logger;

import javax.script.Bindings;
import javax.script.SimpleBindings;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Tests for {@link TagHtlAdapter}.
 *
 * @author Hans-Peter Stoerr
 * @since 09/2017
 */
public class TagHtlAdapterTest {

    private static final Logger LOG = getLogger(TagHtlAdapterTest.class);

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    private Bindings bindings;
    private PrintWriter writer;
    private StringWriter stringWriter;

    @Before
    public void setup() throws Exception {
        Resource resource = context.build().resource("/something").commit().getCurrentParent();
        bindings = new SimpleBindings();
        bindings.put(SlingBindings.REQUEST, context.request());
        bindings.put(SlingBindings.RESOLVER, context.resourceResolver());
        bindings.put(SlingBindings.RESPONSE, context.response());
        bindings.put(SlingBindings.LOG, LOG);
        bindings.put(SlingBindings.RESOURCE, resource);
        SlingScriptHelper scriptHelper = Mockito.spy(context.slingScriptHelper());
        bindings.put(SlingBindings.SLING, scriptHelper);
        SlingScript slingScript = Mockito.mock(SlingScript.class);
        doReturn(slingScript).when(scriptHelper).getScript();
        when(slingScript.getScriptResource()).thenReturn(resource);
        stringWriter = new StringWriter();
        writer = new PrintWriter(stringWriter);
        bindings.put(SlingBindings.OUT, writer);

        context.registerService(XSSFilter.class, Mockito.mock(XSSFilter.class));
        ServiceHandle xssapihandle = (ServiceHandle) FieldUtils.readStaticField(com.composum.sling.core.util.XSS.class, "XSSAPI_HANDLE", true);
        FieldUtils.writeField(xssapihandle, "service", context.registerInjectActivateService(new XSSAPIImpl()), true);

        context.registerService(DynamicClassLoaderManager.class, new DynamicClassLoaderManager() {
            @Override
            public ClassLoader getDynamicClassLoader() {
                return getClass().getClassLoader();
            }
        });
    }

    protected String getWrittenContent() {
        writer.flush();
        return stringWriter.toString();
    }

    @Test
    public void textTag() throws Exception {
        bindings.put(TagHtlAdapter.TAGCLASS, TextTag.class.getName());
        TagHtlAdapter adapter = new TagHtlAdapter();
        adapter.init(bindings);

        bindings.put("tagName", "h4");
        bindings.put("tagClass", "name");
        bindings.put("value", "John");
        assertEquals("", adapter.doStartAndEnd());

        assertEquals("<h4 class=\"name\">John</h4>", getWrittenContent());
    }

}
