package com.composum.sling.platform.staging.service;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.testutil.JcrTestUtils;
import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Before;
import org.junit.Rule;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.VersionManager;
import java.io.IOException;
import java.io.InputStreamReader;

import com.composum.sling.platform.staging.testutil.JcrTestUtils;
import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.jackrabbit.commons.cnd.ParseException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.VersionManager;
import java.io.IOException;
import java.io.InputStreamReader;

import static com.composum.sling.core.util.ResourceUtil.TYPE_UNSTRUCTURED;
import static com.composum.sling.platform.staging.testutil.JcrTestUtils.array;

/**
 * Tests for {@link DefaultStagingReleaseManager}.
 */
public class DefaultStagingReleaseManagerTest extends Assert implements StagingConstants {

    // wee need JCR_OAK for the node type handling - check protected properties etc.
    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private VersionManager versionManager;
    private Session session;
    private ResourceBuilder builder;

    private StagingReleaseManager service = new DefaultStagingReleaseManager();
    private ResourceHandle releaseRoot;

    @Before
    public void setup() throws ParseException, RepositoryException, IOException {
        builder = context.build().withIntermediatePrimaryType(TYPE_UNSTRUCTURED);
        session = context.resourceResolver().adaptTo(Session.class);
        versionManager = session.getWorkspace().getVersionManager();
        InputStreamReader cndReader = new InputStreamReader(getClass().getResourceAsStream("/testsetup/nodetypes.cnd"));
        NodeType[] nodeTypes = CndImporter.registerNodeTypes(cndReader, session);
        assertEquals(3, nodeTypes.length);

        ResourceBuilder releaseRootBuilder = builder.resource("/content/site", ResourceUtil.PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                ResourceUtil.PROP_MIXINTYPES, array(TYPE_MIX_RELEASE_ROOT));
        releaseRoot = ResourceHandle.use(releaseRootBuilder.commit().getCurrentParent());
    }


    @Test
    public void createCurrentRelease() throws RepositoryException, PersistenceException {
        service.updateCurrentReleaseFromWorkspace(releaseRoot);
        JcrTestUtils.printResourceRecursivelyAsJson(releaseRoot);
        assertNotNull(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root"));
        // that'd be an unwanted recursion:
        assertNull(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/jcr:content"));
    }

}
