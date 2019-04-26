package com.composum.sling.platform.staging.service;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.ReleaseNumberCreator;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.StagingReleaseManager.Release;
import com.composum.sling.platform.staging.ReleasedVersionable;
import com.composum.sling.platform.staging.impl.DefaultStagingReleaseManager;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
import com.composum.sling.platform.testing.testutil.AnnotationWithDefaults;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.JcrTestUtils;
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
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.ResourceUtil.*;
import static com.composum.sling.platform.testing.testutil.JcrTestUtils.array;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.exceptionOf;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.throwableWithMessage;
import static org.hamcrest.Matchers.*;

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
    private ResourceBuilder releaseRootBuilder;

    private StagingReleaseManager service;
    private ResourceHandle releaseRoot;
    private Release currentRelease;

    @Before
    public void setup() throws ParseException, RepositoryException, IOException {
        ResourceBuilder builder = context.build().withIntermediatePrimaryType(TYPE_UNSTRUCTURED);
        Session session = context.resourceResolver().adaptTo(Session.class);
        versionManager = session.getWorkspace().getVersionManager();
        InputStreamReader cndReader = new InputStreamReader(getClass().getResourceAsStream("/testsetup/nodetypes.cnd"));
        NodeType[] nodeTypes = CndImporter.registerNodeTypes(cndReader, session);
        assertEquals(3, nodeTypes.length);

        releaseRootBuilder = builder.resource("/content/site", ResourceUtil.PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                ResourceUtil.PROP_MIXINTYPES, array(TYPE_MIX_RELEASE_ROOT));
        releaseRoot = ResourceHandle.use(releaseRootBuilder.commit().getCurrentParent());

        service = new DefaultStagingReleaseManager() {{
            this.resourceResolverFactory = context.getService(ResourceResolverFactory.class);
            this.configuration = AnnotationWithDefaults.of(DefaultStagingReleaseManager.Configuration.class);
        }};

        currentRelease = service.findRelease(releaseRoot, StagingConstants.CURRENT_RELEASE);
    }

    @After
    public void printJcr() {
        if (0 == 0) JcrTestUtils.printResourceRecursivelyAsJson(releaseRoot);
    }


    @Test
    public void createCurrentRelease() {
        List<Release> releases = service.getReleases(releaseRoot);
        assertEquals(1, releases.size());

        assertEquals(CURRENT_RELEASE, currentRelease.getNumber());
        assertNotNull(currentRelease.getUuid());
        assertEquals(releaseRoot, currentRelease.getReleaseRoot());
        assertEquals("/content/site/jcr:content/cpl:releases/cpl:current/metaData", currentRelease.getMetaDataNode().getPath());
        assertNotNull(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root"));
    }

    @Test
    public void addDocument() throws Exception {
        Resource versionable = releaseRootBuilder.resource("a/jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE, TYPE_TITLE, TYPE_LAST_MODIFIED), "foo", "bar", PROP_TITLE, "title")
                .commit().getCurrentParent();
        Version version = versionManager.checkpoint(versionable.getPath());

        service.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(versionable));
        context.resourceResolver().commit();

        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/a/jcr:content"), versionable, version);

        context.resourceResolver().delete(versionable); // make sure stagedresolver doesn't read it from the workspace
        context.resourceResolver().commit();

        ResourceResolver stagedResolver = service.getResolverForRelease(currentRelease, null, false);
        Resource staged = stagedResolver.getResource(versionable.getPath());
        ec.checkThat(String.valueOf(staged), ResourceHandle.use(staged).isValid(), is(true));

        ec.checkThat(version.getContainingHistory().getVersionLabels(version),
                arrayContaining(StagingConstants.RELEASE_LABEL_PREFIX + currentRelease.getNumber().replace("cpl:", "")));
    }

    protected void referenceRefersToVersionableVersion(Resource rawVersionReference, Resource versionable, Version version) throws RepositoryException {
        ResourceHandle versionReference = ResourceHandle.use(rawVersionReference);
        assertTrue(versionReference.isOfType(StagingConstants.TYPE_VERSIONREFERENCE));
        ec.checkThat(ResourceUtil.getReferredResource(versionReference.getChild(PROP_VERSIONABLEUUID)), ResourceMatchers.path(versionable.getPath()));
        ec.checkThat(ResourceUtil.getReferredResource(versionReference.getChild(PROP_VERSION)), ResourceMatchers.path(version.getPath()));
        ec.checkThat(ResourceUtil.getReferredResource(versionReference.getChild(PROP_VERSIONHISTORY)), ResourceMatchers.path(version.getParent().getPath()));
    }

    @Test
    public void createNewReleaseAndMoveDocument() throws Exception {
        // create and check in a version
        Resource versionable = releaseRootBuilder.resource("a/jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE, TYPE_TITLE, TYPE_LAST_MODIFIED), "foo", "bar", PROP_TITLE, "title")
                .commit().getCurrentParent();
        Version version = versionManager.checkpoint(versionable.getPath());
        service.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(versionable));
        context.resourceResolver().commit();

        // check that it's returned as release content and has the right label
        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/a/jcr:content"), versionable, version);
        List<ReleasedVersionable> contents = service.listReleaseContents(currentRelease);
        ec.checkThat(contents.size(), is(1));
        ec.checkThat(contents.get(0).getRelativePath(), is("a/jcr:content"));
        ec.checkThat(contents.get(0).getVersionUuid(), is(version.getUUID()));

        ec.checkThat(version.getContainingHistory().getVersionLabels(version),
                arrayContaining(StagingConstants.RELEASE_LABEL_PREFIX + currentRelease.getNumber().replace("cpl:", "")));

        // a newly created release references the same version.
        Release r1 = service.createRelease(currentRelease, ReleaseNumberCreator.MAJOR);
        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/r1/root/a/jcr:content"), versionable, version);
        ec.checkThat(service.listReleaseContents(currentRelease).size(), is(1));

        // move the document and put a new version of the document into the release
        String newPath = releaseRootBuilder.resource("b/c").commit().getCurrentParent().getPath() + "/jcr:content";
        context.resourceResolver().move(versionable.getPath(), ResourceUtil.getParent(newPath));
        context.resourceResolver().commit();
        Resource newVersionable = context.resourceResolver().getResource(newPath);

        ec.checkThat(service.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(newVersionable)).toString(), equalTo("{}"));
        context.resourceResolver().commit();
        ec.checkThat(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/a"), nullValue()); // parent was now an orphan and is removed
        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/b/c/jcr:content"), newVersionable, version);

        Version version2 = versionManager.checkpoint(newPath);
        // see that updating to a new version works as expected
        ec.checkThat(service.updateRelease(currentRelease,
                Arrays.asList(ReleasedVersionable.forBaseVersion(newVersionable), ReleasedVersionable.forBaseVersion(newVersionable))).toString(),
                equalTo("{}"));
        context.resourceResolver().commit();
        referenceRefersToVersionableVersion(releaseRoot.getChild("jcr:content/cpl:releases/cpl:current/root/b/c/jcr:content"), newVersionable, version2);

        ec.checkThat(version.getContainingHistory().getVersionLabels(version2),
                arrayContaining(StagingConstants.RELEASE_LABEL_PREFIX + currentRelease.getNumber().replace("cpl:", "")));

        // the document is available at the old path in the current release
        ResourceResolver stagedResolver = service.getResolverForRelease(currentRelease, null, false);
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(newPath)).isValid(), is(true));
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(versionable.getPath())).isValid(), is(false));

        // ... but at the old path in the old release.
        stagedResolver = service.getResolverForRelease(r1, null, false);
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(newPath)).isValid(), is(false));
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(versionable.getPath())).isValid(), is(true));

        // check that setting inactive hides the document
        ReleasedVersionable releasedVersionable = ReleasedVersionable.forBaseVersion(newVersionable);
        releasedVersionable.setActive(false);
        ec.checkThat(service.updateRelease(currentRelease, releasedVersionable).toString(), is("{}"));
        stagedResolver = service.getResolverForRelease(currentRelease, null, false);
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(newPath)).isValid(), is(false));
        releasedVersionable.setActive(true);
        ec.checkThat(service.updateRelease(currentRelease, releasedVersionable).toString(), is("{}"));
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(newPath)).isValid(), is(true));

        // delete the document and remove it from the current release, too

        // releasedVersionable.setVersionUuid(null);
        // ec.checkThat(service.updateRelease(currentRelease, releasedVersionable), is("{}"));
        // ec.checkThat(ResourceHandle.use(stagedResolver.getResource(newPath)).isValid(), is(false));

        // context.resourceResolver().delete(newVersionable);
        // context.resourceResolver().commit();

        // versionManager.restore(newPath, version2, false);
        // ec.checkThat(ResourceHandle.use(stagedResolver.getResource(newPath)).isValid(), is(true));

        // crashes: ec.checkThat(versionManager.getVersionHistory(newPath), notNullValue());
        // FIXME hps 2019-04-23 how to do that ? https://stackoverflow.com/questions/55807593
        // we want to delete the document first, and then update it in the release, but can't get the
        // version from VersionManager
    }

    @Test
    public void releaseNumbering() throws Exception {
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

        service.removeRelease(rel101);
        service.removeRelease(rel12);

        ec.checkThat(service.getReleases(releaseRoot).stream()
                        .map(Release::getNumber)
                        .sorted(ReleaseNumberCreator.COMPARATOR_RELEASES)
                        .collect(Collectors.joining(", ")),
                equalTo("cpl:current, r1, r1.1, r2"));

        ec.checkThat(service.findReleaseByUuid(releaseRoot, rel11.getUuid()).getNumber(), equalTo("r1.1"));
    }

    @Test
    public void releaseMarking() throws Exception {
        final Release rel1 = service.createRelease(releaseRoot, ReleaseNumberCreator.MAJOR);
        final Release rel2 = service.createRelease(rel1, ReleaseNumberCreator.MAJOR);
        service.setMark("public", rel1);
        service.setMark("preview", rel1);
        ec.checkThat(service.findReleaseByMark(releaseRoot, "public").getMarks(), containsInAnyOrder("public", "preview"));
        service.setMark("preview", rel2);
        ec.checkThat(service.findReleaseByMark(releaseRoot, "public").getNumber(), is(rel1.getNumber()));
        ec.checkThat(service.findReleaseByMark(releaseRoot, "preview").getNumber(), is(rel2.getNumber()));
        ec.checkThat(service.findReleaseByMark(releaseRoot, "public").getMarks(), contains("public"));
        ec.checkThat(service.findReleaseByMark(releaseRoot, "preview").getMarks(), contains("preview"));
        ec.checkFailsWith(() -> service.removeRelease(rel1), instanceOf(PersistenceException.class));
    }

    @Test
    public void checkOrdering() throws Exception {
        Resource document1 = releaseRootBuilder.resource("document1", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE)).commit().getCurrentParent();
        versionManager.checkpoint(document1.getPath());
        Map<String, SiblingOrderUpdateStrategy.Result> updateResult = service.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(document1));
        ec.checkThat(updateResult.toString(), equalTo("{}"));

        Resource document2 = releaseRootBuilder.resource("document2", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE)).commit().getCurrentParent();
        versionManager.checkpoint(document2.getPath());
        updateResult = service.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(document2));
        ec.checkThat(updateResult.toString(), equalTo("{}"));

        ResourceResolver stagedResolver = service.getResolverForRelease(currentRelease, null, false);
        ec.checkThat(stagedResolver.getResource(releaseRoot.getPath()), ResourceMatchers.containsChildren("jcr:content", "document1", "document2"));

        releaseRoot.getNode().orderBefore("document1", null);

        // no change after staging yet
        ec.checkThat(stagedResolver.getResource(releaseRoot.getPath()), ResourceMatchers.containsChildren("jcr:content", "document1", "document2"));

        updateResult = service.updateRelease(currentRelease, Arrays.asList(ReleasedVersionable.forBaseVersion(document1), ReleasedVersionable.forBaseVersion(document2)));
        ec.checkThat(updateResult.toString(), equalTo("{/content/site=deterministicallyReordered}"));

        ec.checkThat(stagedResolver.getResource(releaseRoot.getPath()), ResourceMatchers.containsChildren("jcr:content", "document2", "document1"));
    }

}
