package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.testutil.JcrTestUtils;
import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.jackrabbit.commons.cnd.ParseException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;
import java.io.IOException;
import java.io.InputStreamReader;

import static com.composum.sling.core.util.ResourceUtil.*;
import static com.composum.sling.platform.staging.testutil.JcrTestUtils.array;
import static org.junit.Assert.assertEquals;

/**
 * Tests for ReleaseTreeSynchronizer that extend the {@link NodeTreeSynchronizer} functionality.
 */
@Ignore
public class ReleaseTreeSynchronizerTest extends NodeTreeSynchronizerTest {

    @Override
    protected NodeTreeSynchronizer createSynchronizer() {
        return new ReleaseTreeSynchronizer();
    }

    @Before
    public void setup() throws ParseException, RepositoryException, IOException {
        Session session = context.resourceResolver().adaptTo(Session.class);
        InputStreamReader cndReader = new InputStreamReader(getClass().getResourceAsStream("/testsetup/nodetypes.cnd"));
        NodeType[] nodeTypes = CndImporter.registerNodeTypes(cndReader, session);
        assertEquals(3, nodeTypes.length);
    }

    // FIXME CORRECT THIS
    @Test
    public void replaceVersionables() throws RepositoryException, PersistenceException {
        VersionManager versionManager = context.resourceResolver().adaptTo(Session.class).getWorkspace().getVersionManager();
        ResourceBuilder builder = context.build().withIntermediatePrimaryType(TYPE_UNSTRUCTURED);
        ResourceBuilder fromBuilder = builder.resource("/s/from", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED);
        Resource versionableResource =
                fromBuilder.resource("a", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, "attra", "vala")
                        .resource("jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES, array(TYPE_TITLE, TYPE_VERSIONABLE))
                        .commit()
                        .getCurrentParent();
        Version version = versionManager.checkpoint(versionableResource.getPath());
        fromBuilder.resource("b", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, "attrb", "valb")
                .resource("c", PROP_PRIMARY_TYPE, "sling:Folder", "attrc", "valc");
        Resource fromResource = fromBuilder.commit().getCurrentParent();

        ResourceBuilder toBuilder = builder.resource("/s/to", PROP_PRIMARY_TYPE, TYPE_SLING_FOLDER);
        toBuilder.resource("deleteme", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED)
                .resource("deletemetoo", PROP_PRIMARY_TYPE, "sling:Folder", "attrc", "valc");
        toBuilder.resource("moveme", PROP_PRIMARY_TYPE, StagingConstants.TYPE_VERSIONREFERENCE,
                StagingConstants.PROP_VERSIONHISTORY, version.getContainingHistory().getUUID(),
                StagingConstants.PROP_VERSION, version.getIdentifier(),
                StagingConstants.PROP_VERSIONABLEUUID, version.getContainingHistory().getVersionableUUID());
        Resource toResource = toBuilder.commit().getCurrentParent();

        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        syncronizer.update(fromResource, toResource);

        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        assertEquals("valc", ResourceHandle.use(fromResource).getProperty("b/c/attrc"));
        assertEquals("valc", ResourceHandle.use(toResource).getProperty("b/c/attrc"));
        assertEquals("vala", ResourceHandle.use(toResource).getProperty("a/attra"));
    }


}
