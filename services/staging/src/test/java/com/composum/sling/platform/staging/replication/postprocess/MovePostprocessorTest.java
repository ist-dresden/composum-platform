package com.composum.sling.platform.staging.replication.postprocess;

import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.replication.ReplicationPaths;
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

/**
 * Test for {@link MovePostprocessor}.
 */
public class MovePostprocessorTest {

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    @Test
    public void checkMove() throws PersistenceException {
        String src = "/the/src";
        String dst = "/our/dst";
        MovePostprocessor processor = new MovePostprocessor(src, dst);

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

        Resource r = top.getCurrentParent();
        processor.postprocess(r);
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

    @Test
    public void checkTranslate() {
        String src = "/the/src";
        String dst = "/our/dst";
        ReplicationPaths replicationPaths = new ReplicationPaths(src, src, dst, null);
        // ec.checkThat(replicationPaths.translate((String) null), nullValue());
        ec.checkThat(replicationPaths.translate(src), is(dst));
        // ec.checkThat(replicationPaths.translate("/whatever"), is("/whatever"));
        ec.checkThat(replicationPaths.translate("/the/src/a/b"), is("/our/dst/a/b"));
        ec.checkThat(replicationPaths.translate("/the/src/a/../b"), is("/our/dst/b"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkTranslateException() {
        ReplicationPaths replicationPaths = new ReplicationPaths("/the/src", "/the/src", "/our/dst", null);
        ec.checkThat(replicationPaths.translate("/whatever"), is("/whatever"));
    }

}
