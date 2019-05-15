package com.composum.sling.platform.staging.versions;

import com.composum.sling.core.filter.ResourceFilter;
import com.composum.sling.core.filter.StringFilter;
import com.composum.sling.platform.security.AccessMode;
import com.composum.sling.platform.staging.ReleaseNumberCreator;
import com.composum.sling.platform.staging.ReleasedVersionable;
import com.composum.sling.platform.staging.StagingReleaseManager.Release;
import com.composum.sling.platform.staging.impl.AbstractStagingTest;
import com.composum.sling.platform.staging.versions.PlatformVersionsService.Status;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.JcrTestUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.regex.Pattern;

import static com.composum.sling.core.util.CoreConstants.*;
import static com.composum.sling.platform.staging.StagingConstants.CURRENT_RELEASE;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_MIX_RELEASE_ROOT;
import static com.composum.sling.platform.testing.testutil.JcrTestUtils.array;
import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.*;

/** Tests for {@link PlatformVersionsServiceImpl}. */
public class PlatformVersionsServiceImplTest extends AbstractStagingTest {

    private String release;
    private ResourceBuilder builderAtRelease;
    private PlatformVersionsService service;

    private String document1;
    private String node1version;
    private Resource versionable;

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures()
            .onFailure(() -> {
                Thread.sleep(500); // wait for logging messages to be written
                JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource(release));
                JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
            });

    @Before
    public void setupServices() throws Exception {
        service = context.registerService(PlatformVersionsService.class, new PlatformVersionsServiceImpl() {{
            this.releaseManager = PlatformVersionsServiceImplTest.this.releaseManager;
        }});
    }

    @Before
    public void setUpContent() throws Exception {
        release = "/content/release";
        builderAtRelease = context.build().resource(release, PROP_PRIMARY_TYPE, TYPE_SLING_ORDERED_FOLDER,
                PROP_MIXINTYPES, array(TYPE_MIX_RELEASE_ROOT)).commit();
        document1 = release + "/" + "document1";
        node1version = makeNode(builderAtRelease, "document1", "n1/something", true, true, "n1 title");
        versionable = context.resourceResolver().getResource(document1).getChild(CONTENT_NODE);
    }

    @Test
    public void defaultRelease() throws Exception {
        ec.checkThat(service.getDefaultRelease(versionable).getNumber(), is(CURRENT_RELEASE));
        Release r1 = releaseManager.createRelease(versionable, ReleaseNumberCreator.MAJOR);
        releaseManager.setMark(AccessMode.ACCESS_MODE_PUBLIC.toLowerCase(), r1);
        context.resourceResolver().commit();
        ec.checkThat(service.getDefaultRelease(versionable).getNumber(), is(CURRENT_RELEASE));
    }

    @Test
    public void initialStatus() throws Exception {
        Resource initVersionable = builderAtRelease.resource("document2", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED)
                .resource(CONTENT_NODE, PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES, new String[]{TYPE_VERSIONABLE, TYPE_LAST_MODIFIED})
                .commit().getCurrentParent();

        Status status = service.getStatus(initVersionable, null);
        ec.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.initial));
        ec.checkThat(status.release(), hasToString("Release('current',/content/release)"));
        ec.checkThat(status.getLastActivatedBy(), nullValue());
        ec.checkThat(status.getLastActivated(), nullValue());
        ec.checkThat(status.getLastDeactivatedBy(), nullValue());
        ec.checkThat(status.getLastDeactivated(), nullValue());
        ec.checkThat(status.getLastModified(), instanceOf(java.util.Calendar.class));
        ec.checkThat(status.getLastModifiedBy(), is("admin"));

        service.activate(null, initVersionable, null);
        context.resourceResolver().commit();

        status = service.getStatus(initVersionable, null);
        ec.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        ec.checkThat(status.release(), hasToString("Release('current',/content/release)"));
        ec.checkThat(status.getLastActivatedBy(), is("admin"));
        ec.checkThat(status.getLastActivated(), instanceOf(java.util.Calendar.class));
    }


    @Test
    public void releaseProgression() throws Exception {
        Status status = service.getStatus(versionable, CURRENT_RELEASE);
        ec.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        ec.checkThat(status.getLastActivatedBy(), is("admin"));
        ec.checkThat(status.getLastActivated(), instanceOf(java.util.Calendar.class));
        ec.checkThat(status.release(), hasToString("Release('current',/content/release)"));

        Release r1 = releaseManager.createRelease(versionable, ReleaseNumberCreator.MAJOR);
        context.resourceResolver().commit();

        Status status1 = service.getStatus(versionable, r1.getNumber());
        ec.checkThat(status1.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        ec.checkThat(status1.release(), hasToString("Release('r1',/content/release)"));
        ec.checkThat(status1.getLastActivated(), is(status.getLastActivated()));

        releaseManager.setMark(AccessMode.ACCESS_MODE_PUBLIC.toLowerCase(), r1);
        context.resourceResolver().commit();

        status1 = service.getStatus(versionable, null); // without release key the current release
        ec.checkThat(status1.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        ec.checkThat(status1.release(), hasToString("Release('current',/content/release)"));
    }

    @Test
    public void status() throws Exception {
        Status status = service.getStatus(versionable, CURRENT_RELEASE);
        ec.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        ec.checkThat(status.release(), hasToString("Release('current',/content/release)"));
        ec.checkThat(status.getLastActivatedBy(), is("admin"));
        ec.checkThat(status.getLastActivated(), instanceOf(java.util.Calendar.class));
        ec.checkThat(status.getLastModified(), instanceOf(java.util.Calendar.class));
        ec.checkThat(status.getLastModifiedBy(), is("admin"));
        versionManager.checkpoint(versionable.getPath());
        versionManager.checkpoint(versionable.getPath());

        status = service.getStatus(versionable, null);
        ec.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.modified));
        ec.checkThat(status.release(), hasToString("Release('current',/content/release)"));
        ec.checkThat(status.getLastDeactivatedBy(), nullValue());
        ec.checkThat(status.getLastDeactivated(), nullValue());

        service.activate(null, versionable, null);
        context.resourceResolver().commit();

        status = service.getStatus(versionable, null);
        ec.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        ec.checkThat(status.getLastActivatedBy(), is("admin"));
        ec.checkThat(status.getLastActivated(), instanceOf(java.util.Calendar.class));
        ec.checkThat(status.getLastDeactivatedBy(), nullValue());
        ec.checkThat(status.getLastDeactivated(), nullValue());

        service.deactivate(null, asList(versionable));
        context.resourceResolver().commit();

        status = service.getStatus(versionable, null);
        ec.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.deactivated));
        ec.checkThat(status.getLastActivatedBy(), is("admin"));
        ec.checkThat(status.getLastActivated(), instanceOf(java.util.Calendar.class));
        ec.checkThat(status.getLastDeactivatedBy(), is("admin"));
        ec.checkThat(status.getLastDeactivated(), instanceOf(java.util.Calendar.class));
        ec.checkThat(status.getLastModifiedBy(), is("admin"));

        ec.checkThat(IteratorUtils.size(versionManager.getVersionHistory(versionable.getPath()).getAllVersions()), is(4));
        service.purgeVersions(versionable);
        // root version (not removeable) and released version
        ec.checkThat(IteratorUtils.size(versionManager.getVersionHistory(versionable.getPath()).getAllVersions()), is(2));
    }

    @Test
    public void activateTwo() throws Exception {
        makeNode(builderAtRelease, "sub/document2", "n2/foo", true, false, "foo");
        makeNode(builderAtRelease, "sub/document3", "n2/foo", true, false, "foo");
        ec.checkThat(releaseManager.listReleaseContents(currentRelease).size(), is(1));
        ResourceResolver resolver = context.resourceResolver();
        PlatformVersionsService.ActivationResult result = service.activate(null, asList(resolver.getResource(release + "/sub/document2"),
                resolver.getResource(release + "/sub/document3/jcr:content")));
        context.resourceResolver().commit();
        ec.checkThat(releaseManager.listReleaseContents(currentRelease).size(), is(3));
        ec.checkThat(result.getChangedPathsInfo().size(), is(0));
    }

    @Test
    public void releaseVersionablesAsResourceFilter() throws Exception {
        ec.checkThat(Pattern.compile(""), notNullValue());
        makeNode(builderAtRelease, "sub/document2", "n2/foo", true, true, "foo");
        String unreleased = makeNode(builderAtRelease, "sub/unreleased", "foo", true, false, "foo");
        String unversioned = makeNode(builderAtRelease, "unversioned", "bar", false, false, "foo");
        Resource nocontentnode = builderAtRelease.resource("other/versionedwithoutcontentnode", PROP_PRIMARY_TYPE,
                TYPE_UNSTRUCTURED, PROP_MIXINTYPES, new String[]{TYPE_VERSIONABLE, TYPE_LAST_MODIFIED}).commit().getCurrentParent();
        versionManager.checkpoint(nocontentnode.getPath());
        releaseManager.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(nocontentnode));
        context.resourceResolver().commit();

        ResourceFilter filter = service.releaseAsResourceFilter(currentRelease.getReleaseRoot(), null, null, null);
        ec.checkThat(filter, hasToString("ResolvedResourceFilter(Release('current',/content/release))"));
        ec.checkThat(filter.isRestriction(), is(false));

        for (String path : asList(document1, versionable.getPath(),
                "/content/release/sub/document2", "/content/release/sub/document2/jcr:content",
                "/content/release/sub/document2/jcr:content/n2/foo",
                nocontentnode.getPath(),
                "/", "/content/release", "/content/release/sub"
        )) {
            ec.checkThat("for path " + path,
                    filter.accept(new SyntheticResource(context.resourceResolver(), path, TYPE_UNSTRUCTURED)), is(true));
        }

        for (String path : asList(unreleased, unversioned, "/content/release/sub/document2/nix")) {
            ec.checkThat("for path " + path,
                    filter.accept(new SyntheticResource(context.resourceResolver(), path, TYPE_UNSTRUCTURED)), is(false));
        }

        filter = service.releaseAsResourceFilter(currentRelease.getReleaseRoot(), null, null, ResourceFilter.ALL);
        ec.checkThat(filter.accept(new SyntheticResource(context.resourceResolver(), "/content/release/sub/document2", TYPE_UNSTRUCTURED)), is(true));

        filter = service.releaseAsResourceFilter(currentRelease.getReleaseRoot(), null, null, ResourceFilter.FilterSet.Rule.none.of(ResourceFilter.ALL));
        ec.checkThat(filter.accept(new SyntheticResource(context.resourceResolver(), "/content/release/sub/document2", TYPE_UNSTRUCTURED)), is(false));
    }

    @Test
    public void releaseVersionablesAsResourceFilterWithContentNodeFilter() throws Exception {
        ResourceFilter filter;
        Release r1 = releaseManager.createRelease(versionable, ReleaseNumberCreator.MAJOR);
        ResourceFilter contentNodeFilter = new ResourceFilter.ContentNodeFilter(true,
                new ResourceFilter.PrimaryTypeFilter(new StringFilter.WhiteList(NT_UNSTRUCTURED)), // normally cpp:Page
                ResourceFilter.ALL);

        filter = service.releaseAsResourceFilter(currentRelease.getReleaseRoot(), r1.getNumber(), null, null);
        ec.checkThat(filter.accept(new SyntheticResource(context.resourceResolver(), document1, TYPE_UNSTRUCTURED)), is(true));
        ec.checkThat(filter.accept(new SyntheticResource(context.resourceResolver(), document1 + "/jcr:content", TYPE_UNSTRUCTURED)), is(true));

        filter = service.releaseAsResourceFilter(currentRelease.getReleaseRoot(), r1.getNumber(), null, contentNodeFilter);
        ec.checkThat(filter, hasToString("ResolvedResourceFilter(Release('r1',/content/release),ContentNode(-,PrimaryType(+'nt:unstructured')=jcr:content=>All()))"));
        ec.checkThat(filter.accept(new SyntheticResource(context.resourceResolver(), document1, TYPE_UNSTRUCTURED)), is(true));
        ec.checkThat(filter.accept(new SyntheticResource(context.resourceResolver(), document1 + "/jcr:content", TYPE_UNSTRUCTURED)), is(false)); // has no content node

        // now remove document 1 from release (deactivate)
        ReleasedVersionable rv = ReleasedVersionable.forBaseVersion(context.resourceResolver().getResource(document1 + "/jcr:content"));
        rv.setActive(false);
        releaseManager.updateRelease(r1, rv);

        filter = service.releaseAsResourceFilter(currentRelease.getReleaseRoot(), r1.getNumber(), null, null);
        ec.checkThat(filter.accept(new SyntheticResource(context.resourceResolver(), document1, TYPE_UNSTRUCTURED)), is(true)); // normally the cpp:Page - still there
        ec.checkThat(filter.accept(new SyntheticResource(context.resourceResolver(), document1 + "/jcr:content", TYPE_UNSTRUCTURED)), is(false)); // only this is removed

        filter = service.releaseAsResourceFilter(currentRelease.getReleaseRoot(), r1.getNumber(), null, contentNodeFilter);
        // its content node is absent -> contentNodeFilter now blocks document1 , normally the cpp:Page
        ec.checkThat(filter.accept(new SyntheticResource(context.resourceResolver(), document1, TYPE_UNSTRUCTURED)), is(false));
    }

}
