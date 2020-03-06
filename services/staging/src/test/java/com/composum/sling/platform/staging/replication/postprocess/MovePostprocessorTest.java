package com.composum.sling.platform.staging.replication.postprocess;

import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.replication.postprocess.MovePostprocessor;
import com.composum.sling.platform.staging.replication.postprocess.MovePostprocessor.MovePropertyReplacer;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.SlingMatchers;
import com.composum.sling.platform.testing.testutil.codegen.SlingAssertionCodeGenerator;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Rule;
import org.junit.Test;

import static com.composum.sling.platform.testing.testutil.SlingMatchers.hasMapSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;

/** Test for {@link MovePostprocessor}. */
public class MovePostprocessorTest {

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    @Test
    public void checkMove() throws PersistenceException {
        String src = "/the/src";
        String dst = "/our/dst";

        ResourceBuilder top = context.build().resource("/whatever",
                ResourceUtil.PROP_PRIMARY_TYPE, ResourceUtil.TYPE_UNSTRUCTURED,
                "int", 17, "sometext", "blabla", "otherpath", "/something/else",
                "srcitself", src, "srcchild", src + "/something"
        );

        Resource c = top.resource("child",
                ResourceUtil.PROP_PRIMARY_TYPE, ResourceUtil.TYPE_UNSTRUCTURED,
                "text", "<p>This page <a href=\"/the/src/bla\">demonstrates</a> that picures and <a " +
                        "href=\"/the/src\">videos</a> can be <a href=\"/something/else\">JCR</a>-versioned.</p>"
        ).commit().getCurrentParent();

        MovePropertyReplacer processor = new MovePropertyReplacer(src, dst);
        Resource r = top.getCurrentParent();
        processor.processResource(r);
        context.resourceResolver().commit();

        new SlingAssertionCodeGenerator("r", r).useErrorCollector()
                .ignoreProperties("getResourceMetadata", "toString").printAssertions();
        ec.checkThat(r.getValueMap(), allOf(
                hasMapSize(6),
                SlingMatchers.hasEntryMatching(is("sometext"), is("blabla")),
                SlingMatchers.hasEntryMatching(is("otherpath"), is("/something/else")),
                SlingMatchers.hasEntryMatching(is("srcitself"), is("/our/dst")),
                SlingMatchers.hasEntryMatching(is("srcchild"), is("/our/dst/something")),
                SlingMatchers.hasEntryMatching(is("jcr:primaryType"), is("nt:unstructured")),
                SlingMatchers.hasEntryMatching(is("int"), is(17L))
        ));

        new SlingAssertionCodeGenerator("c", c).useErrorCollector()
                .ignoreProperties("getResourceMetadata", "toString")
                .printAssertions();
        ec.checkThat(c.getValueMap(), allOf(
                hasMapSize(2),
                SlingMatchers.hasEntryMatching(is("jcr:primaryType"), is("nt:unstructured")),
                SlingMatchers.hasEntryMatching(is("text"), is(
                        "<p>This page <a href=\"/our/dst/bla\">demonstrates</a>" +
                                " that picures and <a href=\"/our/dst\">videos</a> can be <a " +
                                "href=\"/something/else\">JCR</a>-versioned.</p>"))
        ));
    }

}
