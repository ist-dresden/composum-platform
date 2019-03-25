package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.platform.staging.StagingConstants;
import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.jackrabbit.commons.cnd.ParseException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;
import java.io.IOException;
import java.io.InputStreamReader;

import static com.composum.sling.core.util.ResourceUtil.*;
import static com.composum.sling.platform.staging.testutil.JcrTestUtils.array;
import static org.junit.Assert.*;

/**
 * Tests for ReleaseTreeSynchronizer that extend the {@link NodeTreeSynchronizer} functionality.
 */
@Ignore
public class ReleaseTreeSynchronizerTest extends NodeTreeSynchronizerTest {

    private VersionManager versionManager;
    private Session session;

    @Override
    protected NodeTreeSynchronizer createSynchronizer() {
        return new ReleaseTreeSynchronizer();
    }

    @Before
    public void setup() throws ParseException, RepositoryException, IOException {
        session = context.resourceResolver().adaptTo(Session.class);
        versionManager = session.getWorkspace().getVersionManager();
        InputStreamReader cndReader = new InputStreamReader(getClass().getResourceAsStream("/testsetup/nodetypes.cnd"));
        NodeType[] nodeTypes = CndImporter.registerNodeTypes(cndReader, session);
        assertEquals(3, nodeTypes.length);
    }

    /**
     * Tests that a version reference is moved to the proper location.
     */
    @Test
    public void createVersionReferences() throws RepositoryException, PersistenceException {
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

        Resource toResource = toBuilder.commit().getCurrentParent();
        assertNotNull(context.resourceResolver().getResource("/s/to/deleteme/deletemetoo"));

        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        syncronizer.update(fromResource, toResource);
        session.save();

        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        assertEquals(StagingConstants.TYPE_VERSIONREFERENCE, ResourceHandle.use(toResource).getProperty("a/jcr:content/jcr:primaryType"));
        assertEquals(TYPE_UNSTRUCTURED, ResourceHandle.use(toResource).getProperty("a/jcr:primaryType"));
        assertEquals("vala", ResourceHandle.use(toResource).getProperty("a/attra"));
        assertEquals("valc", ResourceHandle.use(fromResource).getProperty("b/c/attrc"));
        assertEquals("valc", ResourceHandle.use(toResource).getProperty("b/c/attrc"));

        assertNull(context.resourceResolver().getResource("/s/to/deleteme/deletemetoo"));
    }

    /**
     * Tests that an existing version reference in the destination is moved to the proper location.
     */
    @Test
    public void moveMetadata() throws RepositoryException, PersistenceException {
        ResourceBuilder builder = context.build().withIntermediatePrimaryType(TYPE_UNSTRUCTURED);
        ResourceBuilder fromBuilder = builder.resource("/s/from", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED);
        Resource versionableResource =
                fromBuilder.resource("a", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, "attra", "vala")
                        .resource("jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES, array(TYPE_TITLE, TYPE_VERSIONABLE))
                        .commit()
                        .getCurrentParent();
        Version firstversion = versionManager.checkpoint(versionableResource.getPath());
        Version version = versionManager.checkpoint(versionableResource.getPath());
        versionManager.checkpoint(versionableResource.getPath()); // create new version but we keep the old one in the reference
        Resource fromResource = fromBuilder.commit().getCurrentParent();

        ResourceBuilder toBuilder = builder.resource("/s/to", PROP_PRIMARY_TYPE, TYPE_SLING_FOLDER);

        // existing version reference with some metadata
        ResourceHandle movemeResource = ResourceHandle.use(
                toBuilder.resource("moveme", PROP_PRIMARY_TYPE, StagingConstants.TYPE_VERSIONREFERENCE,
                        "oldmetadata", "oldmetaval").getCurrentParent());
        movemeResource.setProperty(StagingConstants.PROP_VERSIONHISTORY, version.getContainingHistory().getUUID(), PropertyType.REFERENCE);
        movemeResource.setProperty(StagingConstants.PROP_VERSION, version.getIdentifier(), PropertyType.REFERENCE);
        movemeResource.setProperty(StagingConstants.PROP_VERSIONABLEUUID, version.getContainingHistory().getVersionableUUID(), PropertyType.WEAKREFERENCE);

        Resource toResource = toBuilder.commit().getCurrentParent();
        assertNotNull(context.resourceResolver().getResource("/s/to/moveme"));

        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        syncronizer.update(fromResource, toResource);
        session.save();

        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/s"));

        assertEquals(StagingConstants.TYPE_VERSIONREFERENCE, ResourceHandle.use(toResource).getProperty("a/jcr:content/jcr:primaryType"));
        assertEquals("metadata is still there", "oldmetaval", ResourceHandle.use(toResource).getProperty("a/jcr:content/oldmetadata"));
        assertEquals("version should not be updated", version.getIdentifier(), ResourceHandle.use(toResource).getProperty("a/jcr:content/" + StagingConstants.PROP_VERSION));
        assertEquals(TYPE_UNSTRUCTURED, ResourceHandle.use(toResource).getProperty("a/jcr:primaryType"));
        assertEquals("vala", ResourceHandle.use(toResource).getProperty("a/attra"));

        assertNull(context.resourceResolver().getResource("/s/to/moveme"));

        versionManager.restore(firstversion, false);
    }

}
