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
import com.composum.sling.platform.testing.testutil.codegen.AssertionCodeGenerator;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.jackrabbit.commons.JcrUtils;
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

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures errorCollector = new ErrorCollectorAlwaysPrintingFailures();

    private String release;
    private ResourceBuilder builderAtRelease;
    private StagingReleaseManager releaseManager;
    private PlatformVersionsService service;

    private String document1;
    private String node1version;
    private Resource versionable;

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
