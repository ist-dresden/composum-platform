package com.composum.sling.platform.staging.impl;

import com.composum.sling.platform.staging.testutil.JcrTestUtils;
import com.google.common.collect.ImmutableMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Rule;
import org.junit.Test;

import javax.jcr.RepositoryException;

import static com.composum.sling.core.util.ResourceUtil.*;
import static com.composum.sling.platform.staging.testutil.JcrTestUtils.array;
import static org.junit.Assert.*;

@SuppressWarnings("ConstantConditions")
public class NodeTreeSynchronizerTest {

    private final NodeTreeSynchronizer syncronizer = new NodeTreeSynchronizer();

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    public void syncAttributes() throws RepositoryException {
        Resource fromResource = context.build().resource("/s/from/node1",
                ImmutableMap.of(PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES,
                        array(TYPE_TITLE, TYPE_VERSIONABLE), "bla", "blaval", "blu", 7, PROP_TITLE, "title")).commit().getCurrentParent();
        Resource toResource = context.build().resource("/s/to/node2",
                ImmutableMap.of(PROP_PRIMARY_TYPE, TYPE_SLING_FOLDER,
                        PROP_MIXINTYPES, array(TYPE_LOCKABLE), "foo", "fooval")).commit().getCurrentParent();
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        syncronizer.update(fromResource, toResource);

        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        fromResource = fromResource.getResourceResolver().getResource(fromResource.getPath());
        toResource = fromResource.getResourceResolver().getResource(toResource.getPath());

        assertEquals(getPrimaryType(fromResource), getPrimaryType(toResource));
        assertArrayEquals(fromResource.getValueMap().get(PROP_MIXINTYPES, new String[0]), toResource.getValueMap().get(PROP_MIXINTYPES, new String[0]));

        assertEquals("blaval", toResource.getValueMap().get("bla"));
        assertEquals(7l, toResource.getValueMap().get("blu"));
        assertNull(toResource.getValueMap().get("foo"));
        assertNull(toResource.getValueMap().get(PROP_CREATED));
    }

}
