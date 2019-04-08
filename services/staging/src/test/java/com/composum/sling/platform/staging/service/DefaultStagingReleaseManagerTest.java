package com.composum.sling.platform.staging.service;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.staging.testutil.JcrTestUtils;
import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.jackrabbit.commons.cnd.ParseException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.hamcrest.ResourceMatchers;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.*;
import org.junit.runners.MethodSorters;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

import static com.composum.sling.core.util.ResourceUtil.*;
import static com.composum.sling.platform.staging.testutil.JcrTestUtils.array;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link DefaultStagingReleaseManager}.
 */
@FixMethodOrder(value = MethodSorters.NAME_ASCENDING)
public class DefaultStagingReleaseManagerTest extends Assert implements StagingConstants {

    // wee need JCR_OAK for the node type handling - check protected properties etc.
    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures();

    private VersionManager versionManager;
    private Session session;
    private ResourceBuilder releaseRootBuilder;

    private StagingReleaseManager service;
    private ResourceHandle releaseRoot;

    @Before
    public void setup() throws ParseException, RepositoryException, IOException {
        ResourceBuilder builder = context.build().withIntermediatePrimaryType(TYPE_UNSTRUCTURED);
        session = context.resourceResolver().adaptTo(Session.class);
        versionManager = session.getWorkspace().getVersionManager();
        InputStreamReader cndReader = new InputStreamReader(getClass().getResourceAsStream("/testsetup/nodetypes.cnd"));
        NodeType[] nodeTypes = CndImporter.registerNodeTypes(cndReader, session);
        assertEquals(3, nodeTypes.length);

        releaseRootBuilder = builder.resource("/content/site", ResourceUtil.PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                ResourceUtil.PROP_MIXINTYPES, array(TYPE_MIX_RELEASE_ROOT));
        releaseRoot = ResourceHandle.use(releaseRootBuilder.commit().getCurrentParent());

        service = new DefaultStagingReleaseManager() {{
            this.resourceResolverFactory = context.getService(ResourceResolverFactory.class);
        }};

        assertNotNull(releaseRoot.adaptTo(Node.class));
        assertNotNull(releaseRoot.getChild(PROP_PRIMARY_TYPE).adaptTo(Property.class));
    }

    @After
    public void printJcr() {
        JcrTestUtils.printResourceRecursivelyAsJson(releaseRoot);
    }


    @Test
    public void createCurrentRelease() throws RepositoryException, PersistenceException {
        List<StagingReleaseManager.Release> releases = service.getReleases(releaseRoot);
        assertEquals(1, releases.size());
        StagingReleaseManager.Release currentRelease = service.findRelease(releaseRoot, StagingConstants.NODE_CURRENT_RELEASE);
        assertEquals(NODE_CURRENT_RELEASE, currentRelease.getNumber());
        assertNotNull(currentRelease.getUuid());
        assertEquals(releaseRoot, currentRelease.getReleaseRoot());
        assertEquals("/content/site/jcr:content/cpl:releases/cpl:current/metaData", currentRelease.getMetaDataNode().getPath());
        assertNotNull(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root"));
    }

    @Test
    public void addDocument() throws PersistenceException, RepositoryException {
        StagingReleaseManager.Release currentRelease = service.findRelease(releaseRoot, StagingConstants.NODE_CURRENT_RELEASE);

        Resource versionable = releaseRootBuilder.resource("a/jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE, TYPE_TITLE, TYPE_LAST_MODIFIED), "foo", "bar", PROP_TITLE, "title")
                .commit().getCurrentParent();
        Version version = versionManager.checkpoint(versionable.getPath());

        service.updateRelease(currentRelease, StagingReleaseManager.ReleasedVersionable.forBaseVersion(versionable));
        context.resourceResolver().commit();

        ResourceHandle versionReference = ResourceHandle.use(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/a/jcr:content"));
        assertTrue(versionReference.isOfType(StagingConstants.TYPE_VERSIONREFERENCE));
        ec.checkThat(ResourceUtil.getReferredResource(versionReference.getChild(PROP_VERSIONABLEUUID)), ResourceMatchers.path(versionable.getPath()));
        ec.checkThat(ResourceUtil.getReferredResource(versionReference.getChild(PROP_VERSION)), ResourceMatchers.path(version.getPath()));
        ec.checkThat(ResourceUtil.getReferredResource(versionReference.getChild(PROP_VERSIONHISTORY)), ResourceMatchers.path(version.getParent().getPath()));

        context.resourceResolver().delete(versionable); // make sure stagedresolver doesn't read it from the workspace
        context.resourceResolver().commit();

        ResourceResolver stagedResolver = service.getResolverForRelease(currentRelease, null);
        Resource staged = stagedResolver.getResource(versionable.getPath());
        ec.checkThat(String.valueOf(staged), ResourceHandle.use(staged).isValid(), is(true));
    }

}
