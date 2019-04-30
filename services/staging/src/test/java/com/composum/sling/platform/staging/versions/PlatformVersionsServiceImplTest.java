package com.composum.sling.platform.staging.versions;

import com.composum.sling.platform.security.AccessMode;
import com.composum.sling.platform.staging.ReleaseNumberCreator;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.StagingReleaseManager.Release;
import com.composum.sling.platform.staging.impl.AbstractStagingTest;
import com.composum.sling.platform.staging.impl.DefaultStagingReleaseManager;
import com.composum.sling.platform.staging.versions.PlatformVersionsService.Status;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.JcrTestUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static com.composum.sling.core.util.CoreConstants.*;
import static com.composum.sling.platform.staging.StagingConstants.CURRENT_RELEASE;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_MIX_RELEASE_ROOT;
import static com.composum.sling.platform.testing.testutil.JcrTestUtils.array;
import static org.hamcrest.Matchers.*;

/** Tests for {@link PlatformVersionsServiceImpl}. */
public class PlatformVersionsServiceImplTest extends AbstractStagingTest {

    private String release;
    private ResourceBuilder builderAtRelease;
    private StagingReleaseManager releaseManager;
    private PlatformVersionsService service;

    private String document1;
    private String node1version;
    private Resource versionable;

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures errorCollector = new ErrorCollectorAlwaysPrintingFailures()
            .onFailure(() -> {
                Thread.sleep(500); // wait for logging messages to be written
                JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource(release));
                JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
            });

    @Before
    public void setupServices() throws Exception {
        releaseManager = context.registerService(StagingReleaseManager.class, new DefaultStagingReleaseManager());
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
        node1version = makeNode(builderAtRelease, "document1", "n1/something", true, true, "3 third title");
        versionable = context.resourceResolver().getResource(document1).getChild(CONTENT_NODE);
    }

    @Test
    public void defaultRelease() throws Exception {
        errorCollector.checkThat(service.getDefaultRelease(versionable).getNumber(), is(CURRENT_RELEASE));
        Release r1 = releaseManager.createRelease(versionable, ReleaseNumberCreator.MAJOR);
        releaseManager.setMark(AccessMode.ACCESS_MODE_PUBLIC.toLowerCase(), r1);
        context.resourceResolver().commit();
        errorCollector.checkThat(service.getDefaultRelease(versionable).getNumber(), is(r1.getNumber()));
    }

    @Test
    public void initialStatus() throws Exception {
        Resource initVersionable = builderAtRelease.resource("document2", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED)
                .resource(CONTENT_NODE, PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES, new String[]{TYPE_VERSIONABLE, TYPE_LAST_MODIFIED})
                .commit().getCurrentParent();

        Status status = service.getStatus(initVersionable, null);
        errorCollector.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.initial));
        errorCollector.checkThat(status.release(), hasToString("Release('cpl:current',/content/release)"));
        errorCollector.checkThat(status.getLastActivatedBy(), nullValue());
        errorCollector.checkThat(status.getLastActivated(), nullValue());
        errorCollector.checkThat(status.getLastDeactivatedBy(), nullValue());
        errorCollector.checkThat(status.getLastDeactivated(), nullValue());
        errorCollector.checkThat(status.getLastModified(), instanceOf(java.util.Calendar.class));
        errorCollector.checkThat(status.getLastModifiedBy(), is("admin"));

        service.activate(initVersionable, null, null);
        context.resourceResolver().commit();

        status = service.getStatus(initVersionable, null);
        errorCollector.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        errorCollector.checkThat(status.release(), hasToString("Release('cpl:current',/content/release)"));
        errorCollector.checkThat(status.getLastActivatedBy(), is("admin"));
        errorCollector.checkThat(status.getLastActivated(), instanceOf(java.util.Calendar.class));
    }


    @Test
    public void releaseProgression() throws Exception {
        Status status = service.getStatus(versionable, CURRENT_RELEASE);
        errorCollector.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.modified));
        errorCollector.checkThat(status.release(), hasToString("Release('cpl:current',/content/release)"));

        service.activate(versionable, null, null);
        context.resourceResolver().commit();

        status = service.getStatus(versionable, CURRENT_RELEASE);
        errorCollector.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        errorCollector.checkThat(status.getLastActivatedBy(), is("admin"));
        errorCollector.checkThat(status.getLastActivated(), instanceOf(java.util.Calendar.class));
        errorCollector.checkThat(status.release(), hasToString("Release('cpl:current',/content/release)"));

        Release r1 = releaseManager.createRelease(versionable, ReleaseNumberCreator.MAJOR);
        context.resourceResolver().commit();

        Status status1 = service.getStatus(versionable, r1.getNumber());
        errorCollector.checkThat(status1.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        errorCollector.checkThat(status1.release(), hasToString("Release('r1',/content/release)"));
        errorCollector.checkThat(status1.getLastActivated(), is(status.getLastActivated()));

        releaseManager.setMark(AccessMode.ACCESS_MODE_PUBLIC.toLowerCase(), r1);
        context.resourceResolver().commit();

        status1 = service.getStatus(versionable, null);
        errorCollector.checkThat(status1.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        errorCollector.checkThat(status1.release(), hasToString("Release('r1',/content/release)"));
    }

    @Test
    public void status() throws Exception {
        Status status = service.getStatus(versionable, CURRENT_RELEASE);
        // we have "modified" since no activation date is set. A little doubtable, but not the normal case
        errorCollector.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.modified));
        errorCollector.checkThat(status.release(), hasToString("Release('cpl:current',/content/release)"));
        versionManager.checkpoint(versionable.getPath());
        versionManager.checkpoint(versionable.getPath());

        status = service.getStatus(versionable, null);
        errorCollector.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.modified));
        errorCollector.checkThat(status.release(), hasToString("Release('cpl:current',/content/release)"));
        errorCollector.checkThat(status.getLastActivatedBy(), nullValue());
        errorCollector.checkThat(status.getLastActivated(), nullValue());
        errorCollector.checkThat(status.getLastDeactivatedBy(), nullValue());
        errorCollector.checkThat(status.getLastDeactivated(), nullValue());
        errorCollector.checkThat(status.getLastModified(), instanceOf(java.util.Calendar.class));
        errorCollector.checkThat(status.getLastModifiedBy(), is("admin"));

        service.activate(versionable, null, null);
        context.resourceResolver().commit();

        status = service.getStatus(versionable, null);
        errorCollector.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.activated));
        errorCollector.checkThat(status.getLastActivatedBy(), is("admin"));
        errorCollector.checkThat(status.getLastActivated(), instanceOf(java.util.Calendar.class));
        errorCollector.checkThat(status.getLastDeactivatedBy(), nullValue());
        errorCollector.checkThat(status.getLastDeactivated(), nullValue());

        service.deactivate(versionable, null);
        context.resourceResolver().commit();

        status = service.getStatus(versionable, null);
        errorCollector.checkThat(status.getActivationState(), is(PlatformVersionsService.ActivationState.deactivated));
        errorCollector.checkThat(status.getLastActivatedBy(), is("admin"));
        errorCollector.checkThat(status.getLastActivated(), instanceOf(java.util.Calendar.class));
        errorCollector.checkThat(status.getLastDeactivatedBy(), is("admin"));
        errorCollector.checkThat(status.getLastDeactivated(), instanceOf(java.util.Calendar.class));
        errorCollector.checkThat(status.getLastModifiedBy(), is("admin"));

        errorCollector.checkThat(IteratorUtils.size(versionManager.getVersionHistory(versionable.getPath()).getAllVersions()), is(4));
        service.purgeVersions(versionable);
        // root version (not removeable) and released version
        errorCollector.checkThat(IteratorUtils.size(versionManager.getVersionHistory(versionable.getPath()).getAllVersions()), is(2));
    }


}
