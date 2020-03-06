package com.composum.sling.platform.staging.replication.json;

import com.composum.sling.platform.staging.replication.json.VersionableTree;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;

import static com.composum.sling.core.util.CoreConstants.PROP_MIXINTYPES;
import static com.composum.sling.platform.staging.StagingConstants.PROP_REPLICATED_VERSION;
import static org.apache.jackrabbit.JcrConstants.MIX_VERSIONABLE;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link VersionableTree}
 */
public class VersionableTreeTest {

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    protected void versionable(ResourceBuilder b, String path, String version) {
        b.resource(path, PROP_MIXINTYPES, new String[]{MIX_VERSIONABLE}, PROP_REPLICATED_VERSION, version);
    }

    @Test
    public void normal() {
        ResourceResolver resourceResolver = context.resourceResolver();

        ResourceBuilder b = context.build().resource("/local/content/site");
        versionable(b, "a/jcr:content", "a");
        versionable(b, "b/jcr:content", "b");
        versionable(b, "b/c1/jcr:content", "bc1");
        versionable(b, "b/c2/jcr:content", "bc2");

        b = context.build().resource("/remote/content/site");
        versionable(b, "a/jcr:content", "ax");
        versionable(b, "b/jcr:content", "b");
        versionable(b, "b/c1/jcr:content", "bc1x");
        // versionable(b, "b/c2/jcr:content", "bc2");

        VersionableTree tree = new VersionableTree();
        tree.setSearchtreeRoots(Arrays.asList(resourceResolver.getResource("/local/content/site")));
        Gson gsonSer = new GsonBuilder().registerTypeAdapterFactory(
                new VersionableTree.VersionableTreeSerializer("/local")
        ).create();
        String json = gsonSer.toJson(tree);
        ec.checkThat(json, is("[{\"path\":\"/content/site/a/jcr:content\",\"version\":\"a\"}," +
                "{\"path\":\"/content/site/b/jcr:content\",\"version\":\"b\"}," +
                "{\"path\":\"/content/site/b/c1/jcr:content\",\"version\":\"bc1\"}," +
                "{\"path\":\"/content/site/b/c2/jcr:content\",\"version\":\"bc2\"}]"));

        Gson gsonDeser = new GsonBuilder().registerTypeAdapterFactory(
                new VersionableTree.VersionableTreeDeserializer("/remote", resourceResolver, "/content/site")
        ).create();
        VersionableTree readback = gsonDeser.fromJson(json, VersionableTree.class);
        ec.checkThat(readback, notNullValue());
        ec.checkThat(readback.getChanged().toString(), is("[VersionableInfo[path=/content/site/a/jcr:content,version=a], " +
                "VersionableInfo[path=/content/site/b/c1/jcr:content,version=bc1]]"));
        ec.checkThat(readback.getDeleted().toString(), is("[VersionableInfo[path=/content/site/b/c2/jcr:content,version=bc2]]"));
    }


}
