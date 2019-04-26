package com.composum.sling.platform.staging.versions;

import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.platform.security.AccessMode;
import com.composum.sling.platform.staging.ReleaseNumberCreator;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.StagingReleaseManager.Release;
import com.composum.sling.platform.staging.impl.AbstractStagingTest;
import com.composum.sling.platform.staging.impl.DefaultStagingReleaseManager;
import com.composum.sling.platform.staging.versions.PlatformVersionsService.Status;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static com.composum.sling.core.util.CoreConstants.*;
import static com.composum.sling.platform.staging.StagingConstants.CURRENT_RELEASE;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_MIX_RELEASE_ROOT;
import static com.composum.sling.platform.testing.testutil.JcrTestUtils.array;
import static org.hamcrest.Matchers.is;

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
        errorCollector.checkThat(service.getDefaultRelease(versionable).getNumber(), is(r1.getNumber()));
    }

    @Test
    public void status() throws Exception {
        Status status = service.getStatus(versionable, CURRENT_RELEASE);
    }


}
