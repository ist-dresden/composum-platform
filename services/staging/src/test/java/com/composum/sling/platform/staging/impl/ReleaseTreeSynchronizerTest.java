package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.platform.staging.testutil.JcrTestUtils;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.junit.Test;

import javax.jcr.RepositoryException;

import static com.composum.sling.core.util.ResourceUtil.*;
import static org.junit.Assert.assertEquals;

/**
 * Tests for ReleaseTreeSynchronizer that extend the {@link NodeTreeSynchronizer} functionality.
 */
public class ReleaseTreeSynchronizerTest extends NodeTreeSynchronizerTest {

    @Override
    protected NodeTreeSynchronizer createSynchronizer() {
        return new ReleaseTreeSynchronizer();
    }

    // FIXME CORRECT THIS
    @Test
    public void replaceVersionables() throws RepositoryException, PersistenceException {
        ResourceBuilder builder = context.build().withIntermediatePrimaryType(TYPE_UNSTRUCTURED);
        ResourceBuilder fromBuilder = builder.resource("/s/from", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED);
        fromBuilder.resource("a", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, "attra", "vala");
        fromBuilder.resource("b", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, "attrb", "valb")
                .resource("c", PROP_PRIMARY_TYPE, "sling:Folder", "attrc", "valc");
        Resource fromResource = fromBuilder.commit().getCurrentParent();

        ResourceBuilder toBuilder = builder.resource("/s/to", PROP_PRIMARY_TYPE, TYPE_SLING_FOLDER);
        toBuilder.resource("deleteme", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED)
                .resource("deletemetoo", PROP_PRIMARY_TYPE, "sling:Folder", "attrc", "valc");
        Resource toResource = toBuilder.commit().getCurrentParent();

        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        syncronizer.update(fromResource, toResource);

        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        assertEquals("valc", ResourceHandle.use(fromResource).getProperty("b/c/attrc"));
        assertEquals("valc", ResourceHandle.use(toResource).getProperty("b/c/attrc"));
        assertEquals("vala", ResourceHandle.use(toResource).getProperty("a/attra"));
    }


}
