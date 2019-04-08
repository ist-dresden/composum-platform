package com.composum.sling.platform.staging.service;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.service.StagingReleaseManager.Release;
import com.composum.sling.platform.staging.service.StagingReleaseManager.ReleasedVersionable;
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

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.query.Query;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.ResourceUtil.*;
import static com.composum.sling.platform.staging.testutil.JcrTestUtils.array;
import static com.composum.sling.platform.staging.testutil.SlingMatchers.exceptionOf;
import static com.composum.sling.platform.staging.testutil.SlingMatchers.throwableWithMessage;
import static org.hamcrest.Matchers.equalTo;
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
    private Release currentRelease;

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

        currentRelease = service.findRelease(releaseRoot, StagingConstants.NODE_CURRENT_RELEASE);
    }

    @After
    public void printJcr() {
        JcrTestUtils.printResourceRecursivelyAsJson(releaseRoot);
    }


    @Test
    public void createCurrentRelease() throws RepositoryException, PersistenceException {
        List<Release> releases = service.getReleases(releaseRoot);
        assertEquals(1, releases.size());

        assertEquals(NODE_CURRENT_RELEASE, currentRelease.getNumber());
        assertNotNull(currentRelease.getUuid());
        assertEquals(releaseRoot, currentRelease.getReleaseRoot());
        assertEquals("/content/site/jcr:content/cpl:releases/cpl:current/metaData", currentRelease.getMetaDataNode().getPath());
        assertNotNull(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root"));
    }

    @Test
    public void addDocument() throws PersistenceException, RepositoryException {
        Resource versionable = releaseRootBuilder.resource("a/jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE, TYPE_TITLE, TYPE_LAST_MODIFIED), "foo", "bar", PROP_TITLE, "title")
                .commit().getCurrentParent();
        Version version = versionManager.checkpoint(versionable.getPath());

        service.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(versionable));
        context.resourceResolver().commit();

        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/a/jcr:content"), versionable, version);

        context.resourceResolver().delete(versionable); // make sure stagedresolver doesn't read it from the workspace
        context.resourceResolver().commit();

        ResourceResolver stagedResolver = service.getResolverForRelease(currentRelease, null);
        Resource staged = stagedResolver.getResource(versionable.getPath());
        ec.checkThat(String.valueOf(staged), ResourceHandle.use(staged).isValid(), is(true));
    }

    protected void referenceRefersToVersionableVersion(Resource rawVersionReference, Resource versionable, Version version) throws RepositoryException {
        ResourceHandle versionReference = ResourceHandle.use(rawVersionReference);
        assertTrue(versionReference.isOfType(StagingConstants.TYPE_VERSIONREFERENCE));
        ec.checkThat(ResourceUtil.getReferredResource(versionReference.getChild(PROP_VERSIONABLEUUID)), ResourceMatchers.path(versionable.getPath()));
        ec.checkThat(ResourceUtil.getReferredResource(versionReference.getChild(PROP_VERSION)), ResourceMatchers.path(version.getPath()));
        ec.checkThat(ResourceUtil.getReferredResource(versionReference.getChild(PROP_VERSIONHISTORY)), ResourceMatchers.path(version.getParent().getPath()));
    }

    @Test
    public void createNewReleaseAndmoveDocument() throws PersistenceException, RepositoryException, StagingReleaseManager.ReleaseExistsException {
        Resource versionable = releaseRootBuilder.resource("a/jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE, TYPE_TITLE, TYPE_LAST_MODIFIED), "foo", "bar", PROP_TITLE, "title")
                .commit().getCurrentParent();
        Version version = versionManager.checkpoint(versionable.getPath());

        service.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(versionable));
        context.resourceResolver().commit();

        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/a/jcr:content"), versionable, version);
        List<ReleasedVersionable> contents = service.listReleaseContents(currentRelease);
        ec.checkThat(contents.size(), is(1));
        ec.checkThat(contents.get(0).getRelativePath(), is("a/jcr:content"));
        ec.checkThat(contents.get(0).getVersionUuid(), is(version.getUUID()));

        Release r1 = service.createRelease(currentRelease, ReleaseNumberCreator.MAJOR);
        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/r1/root/a/jcr:content"), versionable, version);
        ec.checkThat(service.listReleaseContents(currentRelease).size(), is(1));

        String newPath = releaseRootBuilder.resource("b/c").commit().getCurrentParent().getPath() + "/jcr:content";
        context.resourceResolver().move(versionable.getPath(), ResourceUtil.getParent(newPath));
        context.resourceResolver().commit();
        Resource newVersionable = context.resourceResolver().getResource(newPath);

        service.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(newVersionable));
        context.resourceResolver().commit();
        assertNull(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/a/jcr:content"));
        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/b/c/jcr:content"), newVersionable, version);

        Version version2 = versionManager.checkpoint(newPath);
        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/b/c/jcr:content"), newVersionable, version);

        service.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(newVersionable));
        context.resourceResolver().commit();
        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/b/c/jcr:content"), newVersionable, version2);

        ResourceResolver stagedResolver = service.getResolverForRelease(currentRelease, null);
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(newPath)).isValid(), is(true));
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(versionable.getPath())).isValid(), is(false));

        stagedResolver = service.getResolverForRelease(r1, null);
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(newPath)).isValid(), is(false));
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(versionable.getPath())).isValid(), is(true));
    }

    @Test
    public void releaseNumbering() throws RepositoryException, PersistenceException, StagingReleaseManager.ReleaseExistsException {
        final Release rel1 = service.createRelease(releaseRoot, ReleaseNumberCreator.MAJOR);
        ec.checkThat(rel1.getNumber(), equalTo("r1"));

        final Release rel101 = service.createRelease(rel1, ReleaseNumberCreator.BUGFIX);
        ec.checkThat(rel101.getNumber(), equalTo("r1.0.1"));

        final Release rel11 = service.createRelease(rel101, ReleaseNumberCreator.MINOR);
        ec.checkThat(rel11.getNumber(), equalTo("r1.1"));

        final Release rel12 = service.createRelease(rel11, ReleaseNumberCreator.MINOR);
        ec.checkThat(rel12.getNumber(), equalTo("r1.2"));

        final Release rel2 = service.createRelease(releaseRoot, ReleaseNumberCreator.MAJOR);
        ec.checkThat(rel2.getNumber(), equalTo("r2"));

        ec.checkThat(exceptionOf(() -> service.createRelease(rel11, ReleaseNumberCreator.MINOR)),
                throwableWithMessage(StagingReleaseManager.ReleaseExistsException.class,
                        "Release already exists: r1.2 for /content/site"));

        ec.checkThat(service.getReleases(releaseRoot).stream()
                        .map(Release::getNumber)
                        .sorted(ReleaseNumberCreator.COMPARATOR_RELEASES)
                        .collect(Collectors.joining(", ")),
                equalTo("cpl:current, r1, r1.0.1, r1.1, r1.2, r2"));
    }

}
