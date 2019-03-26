package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.platform.staging.testutil.JcrTestUtils;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Rule;
import org.junit.Test;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.util.Arrays;

import static com.composum.sling.core.util.ResourceUtil.*;
import static com.composum.sling.platform.staging.testutil.JcrTestUtils.array;
import static org.junit.Assert.*;

@SuppressWarnings("ConstantConditions")
public class NodeTreeSynchronizerTest<T extends NodeTreeSynchronizer> {

    protected final NodeTreeSynchronizer syncronizer = createSynchronizer();

    protected NodeTreeSynchronizer createSynchronizer() {
        return new NodeTreeSynchronizer();
    }

    // wee need JCR_OAK for the node type handling - check protected properties etc.
    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    public void syncAttributes() throws RepositoryException, PersistenceException {
        Resource fromResource = context.build().resource("/s/from",
                PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES,
                array(TYPE_TITLE, TYPE_LAST_MODIFIED), "bla", "blaval", "blu", 7, PROP_TITLE, "title",
                "arrayprop", array("a1", "a2")).commit().getCurrentParent();
        Resource toResource = context.build().resource("/s/to", PROP_PRIMARY_TYPE, TYPE_SLING_FOLDER,
                PROP_MIXINTYPES, array(TYPE_LOCKABLE), "foo", "fooval").commit().getCurrentParent();

        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));
        syncronizer.update(fromResource, toResource);
        toResource.getResourceResolver().adaptTo(Session.class).save();
        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        fromResource = fromResource.getResourceResolver().getResource(fromResource.getPath());
        toResource = fromResource.getResourceResolver().getResource(toResource.getPath());

        assertEquals(getPrimaryType(fromResource), getPrimaryType(toResource));
        assertArrayEquals(fromResource.getValueMap().get(PROP_MIXINTYPES, new String[0]), toResource.getValueMap().get(PROP_MIXINTYPES, new String[0]));

        assertEquals("blaval", toResource.getValueMap().get("bla"));
        assertEquals(Integer.valueOf(7), toResource.getValueMap().get("blu", Integer.class));
        assertEquals(Arrays.asList("a1", "a2"), Arrays.asList(toResource.getValueMap().get("arrayprop", String[].class)));
        assertNull(toResource.getValueMap().get("foo"));
        assertNull(toResource.getValueMap().get(PROP_CREATED));
    }

    @Test
    public void syncChildNodes() throws RepositoryException, PersistenceException {
        ResourceBuilder builder = context.build().withIntermediatePrimaryType(TYPE_UNSTRUCTURED);
        ResourceBuilder fromBuilder = builder.resource("/s/from", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED);
        fromBuilder.resource("a", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, "attra", "vala");
        fromBuilder.resource("b", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, "attrb", "valb")
                .resource("c", PROP_PRIMARY_TYPE, "sling:Folder", "attrc", "valc");
        Resource fromResource = fromBuilder.commit().getCurrentParent();

        ResourceBuilder toBuilder = builder.resource("/s/to", PROP_PRIMARY_TYPE, TYPE_SLING_FOLDER);
        toBuilder.resource("deleteme", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED)
                .resource("deletemetoo", PROP_PRIMARY_TYPE, "sling:Folder", "attrc", "valc");
        toBuilder.resource("a", PROP_PRIMARY_TYPE, TYPE_SLING_FOLDER, "attra", "xxx");
        Resource toResource = toBuilder.commit().getCurrentParent();

        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));
        syncronizer.update(fromResource, toResource);
        toResource.getResourceResolver().adaptTo(Session.class).save();
        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        assertEquals("valc", ResourceHandle.use(fromResource).getProperty("b/c/attrc"));
        assertEquals("valc", ResourceHandle.use(toResource).getProperty("b/c/attrc"));
        assertEquals("vala", ResourceHandle.use(toResource).getProperty("a/attra"));
    }

    /** Synchronize a tree into a subtree (used for the release mechanism) - should at least avoid infinite recursion. */
    @Test
    public void overlappingSync() throws RepositoryException, PersistenceException {
        ResourceBuilder builder = context.build().withIntermediatePrimaryType(TYPE_UNSTRUCTURED);
        ResourceBuilder fromBuilder = builder.resource("/s/from", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, "attrroot", "rootval");
        Resource toResource = fromBuilder.resource("a", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED).getCurrentParent();
        fromBuilder.resource("b", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, "attrb", "valb");
        builder.commit();

        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));
        syncronizer.update(fromBuilder.getCurrentParent(), toResource);
        toResource.getResourceResolver().adaptTo(Session.class).save();
        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        assertEquals("rootval", ResourceHandle.use(toResource).getProperty("attrroot"));
        assertEquals("valb", ResourceHandle.use(toResource).getProperty("b/attrb"));
        // no infinite recursion:
        assertNull(ResourceHandle.use(toResource).getChild("a/a"));
        // from/a/a exists, but that's a bit hard to prevent and
    }

}
