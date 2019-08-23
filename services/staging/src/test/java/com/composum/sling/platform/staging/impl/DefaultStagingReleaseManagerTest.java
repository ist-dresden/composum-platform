package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.ReleaseChangeEventListener;
import com.composum.sling.platform.staging.ReleaseChangeEventPublisher;
import com.composum.sling.platform.staging.ReleaseNumberCreator;
import com.composum.sling.platform.staging.ReleasedVersionable;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.StagingReleaseManager.Release;
import com.composum.sling.platform.staging.query.QueryBuilder;
import com.composum.sling.platform.staging.query.impl.QueryBuilderAdapterFactory;
import com.composum.sling.platform.testing.testutil.AnnotationWithDefaults;
import com.composum.sling.platform.testing.testutil.AroundActionsWrapper;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.JcrTestUtils;
import com.google.common.base.Function;
import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.jackrabbit.commons.cnd.ParseException;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.hamcrest.ResourceMatchers;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.ResourceUtil.PROP_MIXINTYPES;
import static com.composum.sling.core.util.ResourceUtil.PROP_PRIMARY_TYPE;
import static com.composum.sling.core.util.ResourceUtil.PROP_TITLE;
import static com.composum.sling.core.util.ResourceUtil.TYPE_LAST_MODIFIED;
import static com.composum.sling.core.util.ResourceUtil.TYPE_TITLE;
import static com.composum.sling.core.util.ResourceUtil.TYPE_UNSTRUCTURED;
import static com.composum.sling.core.util.ResourceUtil.TYPE_VERSIONABLE;
import static com.composum.sling.platform.testing.testutil.JcrTestUtils.array;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.exceptionOf;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.throwableWithMessage;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultStagingReleaseManager}.
 */
public class DefaultStagingReleaseManagerTest extends Assert implements StagingConstants {

    // wee need JCR_OAK for the node type handling - check protected properties etc.
    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures().onFailure(this::printJcr);

    protected void printJcr() {
        try {
            Thread.sleep(500); // wait for logging messages to be written
        } catch (InterruptedException e) { // haha.
            throw new RuntimeException(e);
        }
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/content"));
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource(RELEASE_ROOT_PATH));
        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
    }

    private VersionManager versionManager;
    private ResourceBuilder releaseRootBuilder;

    private StagingReleaseManager service;
    private ResourceHandle releaseRoot;
    private Resource releaseStorageRoot;
    private Release currentRelease;
    private final ReleaseChangeEventPublisher releaseChangeEventPublisher = mock(ReleaseChangeEventPublisher.class);

    @Before
    public void setup() throws ParseException, RepositoryException, IOException, LoginException {
        ResourceBuilder builder = context.build().withIntermediatePrimaryType(TYPE_UNSTRUCTURED);
        Session session = context.resourceResolver().adaptTo(Session.class);
        versionManager = session.getWorkspace().getVersionManager();
        InputStreamReader cndReader = new InputStreamReader(getClass().getResourceAsStream("/testsetup/nodetypes.cnd"));
        NodeType[] nodeTypes = CndImporter.registerNodeTypes(cndReader, session);
        assertEquals(2, nodeTypes.length);

        releaseRootBuilder = builder.resource("/content/site", ResourceUtil.PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                ResourceUtil.PROP_MIXINTYPES, array(TYPE_MIX_RELEASE_ROOT));
        releaseRootBuilder.resource(ResourceUtil.CONTENT_NODE);
        releaseRoot = ResourceHandle.use(releaseRootBuilder.commit().getCurrentParent());

        service = new DefaultStagingReleaseManager() {{
            this.configuration = AnnotationWithDefaults.of(DefaultStagingReleaseManager.Configuration.class);
            this.publisher = releaseChangeEventPublisher;
            this.resolverFactory = mock(ResourceResolverFactory.class);
            when(this.resolverFactory.getServiceResourceResolver(null)).thenAnswer((x) -> context.resourceResolver().clone(null));
        }};
        // Make sure we check each time that the JCR repository is consistent and avoid weird errors
        // that happen because queries don't find uncommitted values.
        // In practice the stuff is usually done in separate transactions, anyway.
        service = AroundActionsWrapper.of(service, this::commitAndCheck, this::commitAndCheck, this::printJcr);

        context.registerAdapter(ResourceResolver.class, QueryBuilder.class,
                (Function<ResourceResolver, QueryBuilder>) (resolver) ->
                        new QueryBuilderAdapterFactory().getAdapter(resolver, QueryBuilder.class));

        currentRelease = service.findRelease(releaseRoot, StagingConstants.CURRENT_RELEASE);

        releaseStorageRoot = context.resourceResolver().getResource("/var/composum/content/site/cpl:releases");
        assertNotNull(releaseStorageRoot);
    }

    @Test
    public void createCurrentRelease() {
        List<Release> releases = service.getReleases(releaseRoot);
        assertEquals(1, releases.size());

        assertEquals(CURRENT_RELEASE, currentRelease.getNumber());
        assertNotNull(currentRelease.getUuid());
        assertEquals(releaseRoot, currentRelease.getReleaseRoot());
        assertEquals("/var/composum/content/site/cpl:releases/current/metaData", currentRelease.getMetaDataNode().getPath());
        Resource resource = context.resourceResolver().getResource("/var/composum/content/site/cpl:releases/current/root");
        assertNotNull(resource);
        assertEquals(currentRelease, service.findReleaseByReleaseResource(resource));
    }

    @Test
    public void addAndRemoveDocument() throws Exception {
        Resource versionable = releaseRootBuilder.resource("a/jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE, TYPE_TITLE, TYPE_LAST_MODIFIED), "foo", "bar", PROP_TITLE, "title")
                .commit().getCurrentParent();
        Version version = versionManager.checkpoint(versionable.getPath());

        ReleasedVersionable releasedVersionable = ReleasedVersionable.forBaseVersion(versionable);
        service.updateRelease(currentRelease, Collections.singletonList(releasedVersionable));

        ArgumentCaptor<ReleaseChangeEventListener.ReleaseChangeEvent> eventCaptor = ArgumentCaptor.forClass(ReleaseChangeEventListener.ReleaseChangeEvent.class);
        Mockito.verify(releaseChangeEventPublisher, times(1)).publishActivation(eventCaptor.capture());
        ec.checkThat(eventCaptor.getValue().toString(), eventCaptor.getValue().release(), is(currentRelease));
        ec.checkThat(eventCaptor.getValue().toString(), eventCaptor.getValue().newResources(), contains(versionable.getPath()));
        Mockito.reset(releaseChangeEventPublisher);

        referenceRefersToVersionableVersion(releaseStorageRoot.getChild("current/root/a/jcr:content"), versionable, version);

        context.resourceResolver().delete(versionable); // make sure stagedresolver doesn't read it from the workspace

        ResourceResolver stagedResolver = service.getResolverForRelease(currentRelease, null, false);
        Resource staged = stagedResolver.getResource(versionable.getPath());
        ec.checkThat(String.valueOf(staged), ResourceHandle.use(staged).isValid(), is(true));

        ec.checkThat(version.getContainingHistory().getVersionLabels(version),
                arrayContaining(StagingConstants.RELEASE_LABEL_PREFIX + currentRelease.getNumber()));

        // deactivate it
        releasedVersionable.setActive(false);
        service.updateRelease(currentRelease, Arrays.asList(releasedVersionable));

        // check that the right event is sent
        eventCaptor = ArgumentCaptor.forClass(ReleaseChangeEventListener.ReleaseChangeEvent.class);
        Mockito.verify(releaseChangeEventPublisher, times(1)).publishActivation(eventCaptor.capture());
        ec.checkThat(eventCaptor.getValue().toString(), eventCaptor.getValue().release(), is(currentRelease));
        ec.checkThat(eventCaptor.getValue().toString(), eventCaptor.getValue().removedOrMovedResources(), contains(versionable.getPath()));
        Mockito.reset(releaseChangeEventPublisher);

        // activate it again
        releasedVersionable.setActive(true);
        service.updateRelease(currentRelease, Arrays.asList(releasedVersionable));

        // check that the right event is sent
        eventCaptor = ArgumentCaptor.forClass(ReleaseChangeEventListener.ReleaseChangeEvent.class);
        Mockito.verify(releaseChangeEventPublisher, times(1)).publishActivation(eventCaptor.capture());
        ec.checkThat(eventCaptor.getValue().toString(), eventCaptor.getValue().release(), is(currentRelease));
        ec.checkThat(eventCaptor.getValue().toString(), eventCaptor.getValue().newOrMovedResources(), contains(versionable.getPath()));
        Mockito.reset(releaseChangeEventPublisher);

        // remove it from the release completely.
        releasedVersionable.setVersionUuid(null); // instruction to remove it
        service.updateRelease(currentRelease, Arrays.asList(releasedVersionable));
        ec.checkThat(stagedResolver.getResource(versionable.getPath()), nullValue());

        Mockito.verify(releaseChangeEventPublisher, times(1)).publishActivation(eventCaptor.capture());
        ec.checkThat(eventCaptor.getValue().toString(), eventCaptor.getValue().release(), is(currentRelease));
        ec.checkThat(eventCaptor.getValue().toString(), eventCaptor.getValue().removedOrMovedResources(), contains(versionable.getPath()));

    }

    protected void referenceRefersToVersionableVersion(Resource rawVersionReference, Resource versionable, Version version) throws RepositoryException {
        ResourceHandle versionReference = ResourceHandle.use(rawVersionReference);
        ec.checkThat(rawVersionReference.getPath(), versionReference.isOfType(StagingConstants.TYPE_VERSIONREFERENCE), is(true));
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
        service.updateRelease(currentRelease, Collections.singletonList(ReleasedVersionable.forBaseVersion(versionable)));

        // check that it's returned as release content and has the right label
        referenceRefersToVersionableVersion(releaseStorageRoot.getChild("current/root/a/jcr:content"), versionable, version);
        List<ReleasedVersionable> contents = service.listReleaseContents(currentRelease);
        ec.checkThat(contents.size(), is(1));
        ec.checkThat(contents.get(0).getRelativePath(), is("a/jcr:content"));
        ec.checkThat(contents.get(0).getVersionUuid(), is(version.getUUID()));

        ReleasedVersionable foundReleasedVersionable = service.findReleasedVersionableByUuid(currentRelease, version.getContainingHistory().getIdentifier());
        ec.checkThat(foundReleasedVersionable.getRelativePath(), is("a/jcr:content"));
        ec.checkThat(foundReleasedVersionable.getVersionUuid(), is(version.getUUID()));

        foundReleasedVersionable = service.findReleasedVersionable(currentRelease, versionable);
        ec.checkThat(foundReleasedVersionable.getRelativePath(), is("a/jcr:content"));
        ec.checkThat(foundReleasedVersionable.getVersionUuid(), is(version.getUUID()));

        ec.checkThat(version.getContainingHistory().getVersionLabels(version),
                arrayContaining(StagingConstants.RELEASE_LABEL_PREFIX + currentRelease.getNumber()));

        // a newly created release references the same version.
        Release r1 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MAJOR);
        ec.checkThat(r1.getNumber(), is("r1"));
        referenceRefersToVersionableVersion(releaseStorageRoot.getChild("r1/root/a/jcr:content"), versionable, version);
        ec.checkThat(service.listReleaseContents(currentRelease).size(), is(1));
        ec.checkThat(service.listReleaseContents(r1).size(), is(1));
        currentRelease = service.findRelease(releaseRoot, CURRENT_RELEASE); // refresh since its nodes were exchanged. Triggers a bug otherwise

        // move the document and put a new version of the document into the current release
        String newPath = releaseRootBuilder.resource("b/c").commit().getCurrentParent().getPath() + "/jcr:content";
        context.resourceResolver().move(versionable.getPath(), ResourceUtil.getParent(newPath));
        Resource newVersionable = context.resourceResolver().getResource(newPath);

        ec.checkThat(service.updateRelease(currentRelease, Collections.singletonList(ReleasedVersionable.forBaseVersion(newVersionable))).toString(), equalTo("{}"));
        ec.checkThat(releaseStorageRoot.getChild("current/root/a"), nullValue()); // parent was now an orphan and is removed
        referenceRefersToVersionableVersion(releaseStorageRoot.getChild("current/root/b/c/jcr:content"), newVersionable, version);

        Version version2 = versionManager.checkpoint(newPath);
        // see that updating to a new version works as expected
        ec.checkThat(service.updateRelease(currentRelease,
                Arrays.asList(ReleasedVersionable.forBaseVersion(newVersionable), ReleasedVersionable.forBaseVersion(newVersionable))).toString(),
                equalTo("{}"));
        referenceRefersToVersionableVersion(releaseStorageRoot.getChild("current/root/b/c/jcr:content"), newVersionable, version2);

        ec.checkThat(version.getContainingHistory().getVersionLabels(version2),
                arrayContaining(StagingConstants.RELEASE_LABEL_PREFIX + currentRelease.getNumber()));

        // the document is available at the new path in the current release
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
        ec.checkThat(service.updateRelease(currentRelease, Collections.singletonList(releasedVersionable)).toString(), is("{}"));
        stagedResolver = service.getResolverForRelease(currentRelease, null, false);
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(newPath)).isValid(), is(false));
        releasedVersionable.setActive(true);
        ec.checkThat(service.updateRelease(currentRelease, Collections.singletonList(releasedVersionable)).toString(), is("{}"));
        ec.checkThat(ResourceHandle.use(stagedResolver.getResource(newPath)).isValid(), is(true));

        Mockito.verify(releaseChangeEventPublisher, atLeastOnce()).publishActivation(any());
    }

    /**
     * @deprecated this test does not correspond to the current idea of handling things, but we keep this around since
     * things might change again... :-)  The real thing is {@link #finalizeCurrentRelease()}.
     */
    @Test
    @Deprecated
    public void releaseNumbering() throws Exception {
        final Release rel1 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MAJOR);
        ec.checkThat(rel1.getNumber(), equalTo("r1"));

        final Release rel101 = service.createRelease(rel1, ReleaseNumberCreator.BUGFIX);
        ec.checkThat(rel101.getNumber(), equalTo("r1.0.1"));

        final Release rel11 = service.createRelease(rel101, ReleaseNumberCreator.MINOR);
        ec.checkThat(rel11.getNumber(), equalTo("r1.1"));

        final Release rel12 = service.createRelease(rel11, ReleaseNumberCreator.MINOR);
        ec.checkThat(rel12.getNumber(), equalTo("r1.2"));

        final Release rel2 = service.createRelease(rel12, ReleaseNumberCreator.MAJOR);
        ec.checkThat(rel2.getNumber(), equalTo("r2"));

        ec.checkThat(exceptionOf(() -> service.createRelease(rel11, ReleaseNumberCreator.MINOR)),
                throwableWithMessage(StagingReleaseManager.ReleaseExistsException.class,
                        "Release already exists: r1.2 for /content/site"));

        ec.checkThat(service.getReleases(releaseRoot).stream()
                        .map(Release::getNumber)
                        .sorted(ReleaseNumberCreator.COMPARATOR_RELEASES)
                        .collect(Collectors.joining(", ")),
                equalTo("r1, r1.0.1, r1.1, r1.2, r2, current"));

        service.deleteRelease(rel101);
        service.deleteRelease(rel12);

        ec.checkThat(service.getReleases(releaseRoot).stream()
                        .map(Release::getNumber)
                        .sorted(ReleaseNumberCreator.COMPARATOR_RELEASES)
                        .collect(Collectors.joining(", ")),
                equalTo("r1, r1.1, r2, current"));

        ec.checkThat(service.findReleaseByUuid(releaseRoot, rel11.getUuid()).getNumber(), equalTo("r1.1"));
    }

    @Test
    public void finalizeCurrentRelease() throws Exception {
        final Release rel1 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MAJOR);
        ec.checkThat(rel1.getNumber(), equalTo("r1"));

        Release rel101 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.BUGFIX);
        ec.checkThat(rel101.getNumber(), equalTo("r1.0.1"));

        final Release rel11 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MINOR);
        ec.checkThat(rel11.getNumber(), equalTo("r1.1"));

        final Release rel12 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MINOR);
        ec.checkThat(rel12.getNumber(), equalTo("r1.2"));

        final Release rel2 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MAJOR);
        ec.checkThat(rel2.getNumber(), equalTo("r2"));

        ec.checkThat(service.getReleases(releaseRoot).stream()
                        .map(Release::getNumber)
                        .sorted(ReleaseNumberCreator.COMPARATOR_RELEASES)
                        .collect(Collectors.joining(", ")),
                equalTo("r1, r1.0.1, r1.1, r1.2, r2, current"));

        service.deleteRelease(rel101);
        service.deleteRelease(rel12);

        ec.checkThat(service.getReleases(releaseRoot).stream()
                        .map(Release::getNumber)
                        .sorted(ReleaseNumberCreator.COMPARATOR_RELEASES)
                        .collect(Collectors.joining(", ")),
                equalTo("r1, r1.1, r2, current"));

        ec.checkThat(service.findReleaseByUuid(releaseRoot, rel11.getUuid()).getNumber(), equalTo("r1.1"));

        currentRelease = service.resetCurrentTo(rel1);
        ec.checkThat(currentRelease.getNumber(), is(CURRENT_RELEASE));
        ec.checkThat(currentRelease.getPreviousRelease().getNumber(), is(rel1.getNumber()));

        ec.checkThat(exceptionOf(() -> service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MAJOR)),
                throwableWithMessage(StagingReleaseManager.ReleaseExistsException.class,
                        "Release already exists: r2 for /content/site"));

        ec.checkThat(exceptionOf(() -> service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MINOR)),
                throwableWithMessage(StagingReleaseManager.ReleaseExistsException.class,
                        "Release already exists: r1.1 for /content/site"));

        rel101 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.BUGFIX);
        ec.checkThat(rel101.getNumber(), is("r1.0.1"));
    }

    @Test
    public void releaseMarking() throws Exception {
        final Release rel1 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MAJOR);
        final Release rel2 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MAJOR);
        service.setMark("public", rel1);
        service.setMark("preview", rel1);
        ec.checkThat(service.findReleaseByMark(releaseRoot, "public").getMarks(), containsInAnyOrder("public", "preview"));
        service.setMark("preview", rel2);
        ec.checkThat(service.findReleaseByMark(releaseRoot, "public").getNumber(), is(rel1.getNumber()));
        ec.checkThat(service.findReleaseByMark(releaseRoot, "preview").getNumber(), is(rel2.getNumber()));
        ec.checkThat(service.findReleaseByMark(releaseRoot, "public").getMarks(), contains("public"));
        ec.checkThat(service.findReleaseByMark(releaseRoot, "preview").getMarks(), contains("preview"));
        ec.checkFailsWith(() -> service.deleteRelease(rel1), instanceOf(StagingReleaseManager.ReleaseProtectedException.class));

        service.deleteMark("public", rel1);
        ec.checkThat(service.findReleaseByMark(releaseRoot, "public"), nullValue());
    }

    @Test
    public void checkOrdering() throws Exception {
        Resource document1 = releaseRootBuilder.resource("document1", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE)).commit().getCurrentParent();
        versionManager.checkpoint(document1.getPath());
        Map<String, SiblingOrderUpdateStrategy.Result> updateResult = service.updateRelease(currentRelease, Collections.singletonList(ReleasedVersionable.forBaseVersion(document1)));
        ec.checkThat(updateResult.toString(), equalTo("{}"));

        Resource document2 = releaseRootBuilder.resource("document2", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE)).commit().getCurrentParent();
        versionManager.checkpoint(document2.getPath());
        updateResult = service.updateRelease(currentRelease, Collections.singletonList(ReleasedVersionable.forBaseVersion(document2)));
        ec.checkThat(updateResult.toString(), equalTo("{}"));

        ResourceResolver stagedResolver = service.getResolverForRelease(currentRelease, null, false);
        ec.checkThat(releaseRoot.getPath(), stagedResolver.getResource(releaseRoot.getPath()), ResourceMatchers.containsChildren("jcr:content", "document1", "document2"));

        releaseRoot.getNode().orderBefore("document1", null);

        // no change after staging yet
        ec.checkThat(stagedResolver.getResource(releaseRoot.getPath()), ResourceMatchers.containsChildren("jcr:content", "document1", "document2"));

        updateResult = service.updateRelease(currentRelease, Arrays.asList(ReleasedVersionable.forBaseVersion(document1), ReleasedVersionable.forBaseVersion(document2)));
        ec.checkThat(updateResult.toString(), equalTo("{/content/site=deterministicallyReordered}"));

        ec.checkThat(stagedResolver.getResource(releaseRoot.getPath()), ResourceMatchers.containsChildren("jcr:content", "document2", "document1"));
    }

    @Test
    public void listCurrentContent() throws Exception {
        List<ReleasedVersionable> content = service.listCurrentContents(this.releaseRoot);
        ec.checkThat(content.size(), is(0));

        Resource versionable = releaseRootBuilder.resource("a/jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE, TYPE_TITLE, TYPE_LAST_MODIFIED), "foo", "bar", PROP_TITLE, "title")
                .commit().getCurrentParent();

        content = service.listCurrentContents(this.releaseRoot);
        ec.checkThat(content.size(), is(1));
        ec.checkThat(content.get(0).getRelativePath(), is("a/jcr:content"));
    }

    @Test
    public void restoreDeletedDocument() throws Exception {
        Resource versionable = releaseRootBuilder.resource("a/jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE, TYPE_TITLE, TYPE_LAST_MODIFIED), "foo", "bar", PROP_TITLE, "title")
                .commit().getCurrentParent();
        String versionablePath = versionable.getPath();
        Version version = versionManager.checkpoint(versionable.getPath());

        service.updateRelease(currentRelease, Collections.singletonList(ReleasedVersionable.forBaseVersion(versionable)));

        ec.checkThat(service.listReleaseContents(currentRelease), hasSize(1));
        ec.checkFailsWith(() -> service.restoreVersionable(currentRelease, service.listReleaseContents(currentRelease).get(0)), instanceOf(IllegalArgumentException.class));

        // delete the document

        context.resourceResolver().delete(versionable);
        ec.checkThat(context.resourceResolver().getResource(versionablePath), nullValue());

        List<ReleasedVersionable> releasedVersionables = service.listReleaseContents(currentRelease);
        ec.checkThat(releasedVersionables, hasSize(1));
        ReleasedVersionable deletedVersionable = releasedVersionables.get(0);

        ReleasedVersionable result = service.restoreVersionable(currentRelease, deletedVersionable);
        ec.checkThat(result.getVersionUuid(), is(deletedVersionable.getVersionUuid()));
        ec.checkThat(context.resourceResolver().getResource(versionablePath), notNullValue());

        // already restored / still exists
        ec.checkFailsWith(() -> service.restoreVersionable(currentRelease, deletedVersionable), instanceOf(IllegalArgumentException.class));
    }

    @Test
    public void deleteRelease() throws Exception {
        Resource versionable = releaseRootBuilder.resource("a/jcr:content", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE, TYPE_TITLE, TYPE_LAST_MODIFIED), "foo", "bar", PROP_TITLE, "title")
                .commit().getCurrentParent();
        versionManager.checkpoint(versionable.getPath());

        service.updateRelease(currentRelease, Collections.singletonList(ReleasedVersionable.forBaseVersion(versionable)));
        Release r1 = service.finalizeCurrentRelease(releaseRoot, ReleaseNumberCreator.MAJOR);

        ec.checkThat(versionManager.getVersionHistory(versionable.getPath()).getVersionLabels(), arrayContaining("composum-release-current", "composum-release-r1"));

        Release r11 = service.createRelease(r1, ReleaseNumberCreator.MINOR);

        ec.checkThat(versionManager.getVersionHistory(versionable.getPath()).getVersionLabels(),
                arrayContainingInAnyOrder("composum-release-current", "composum-release-r1", "composum-release-r1.1"));

        service.setMark("public", r1);
        ec.checkFailsWith(() -> service.deleteRelease(r1), instanceOf(StagingReleaseManager.ReleaseProtectedException.class));
        service.deleteMark("public", r1);
        Release r1n = service.findRelease(releaseRoot, r1.getNumber());
        service.deleteRelease(r1n);

        ec.checkFailsWith(() -> service.findRelease(releaseRoot, r1.getNumber()), instanceOf(StagingReleaseManager.ReleaseNotFoundException.class));

        ec.checkThat(versionManager.getVersionHistory(versionable.getPath()).getVersionLabels(),
                arrayContainingInAnyOrder("composum-release-current", "composum-release-r1.1")); // label is gone
    }

    protected void commitAndCheck() throws PersistenceException, RepositoryException {
        releaseRootBuilder.commit();
        ec.checkThat(AroundActionsWrapper.retrieveWrappedObject(service).cleanupLabels(releaseRoot), is(0));
    }

}
