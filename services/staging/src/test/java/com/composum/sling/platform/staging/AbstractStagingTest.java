package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.mapping.MappingRules;
import com.composum.sling.core.util.JsonUtil;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.service.DefaultStagingReleaseManager;
import com.composum.sling.platform.staging.service.ReleaseMapper;
import com.composum.sling.platform.staging.service.StagingReleaseManager;
import com.composum.sling.platform.staging.testutil.JcrTestUtils;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.lang3.RandomUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.hamcrest.CustomMatcher;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.Mockito;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Calendar;

import static com.composum.sling.core.util.ResourceUtil.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Some common functionality for tests of the Staging functions.
 */
public abstract class AbstractStagingTest {


    public static final String SELECTED_NODETYPE = "rep:Unstructured";
    public static final String[] SELECTED_NODE_MIXINS = {TYPE_CREATED, TYPE_LAST_MODIFIED, TYPE_TITLE};

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    protected static final String RELEASED = "theRelease";
    protected StagingResourceResolverImpl stagingResourceResolver;
    protected VersionManager versionManager;
    protected ReleaseMapper releaseMapper;

    protected final StagingReleaseManager releaseManager = new DefaultStagingReleaseManager();

    @Before
    public final void setUpResolver() throws Exception {
        versionManager = context.resourceResolver().adaptTo(Session.class).getWorkspace().getVersionManager();

        releaseMapper = Mockito.mock(ReleaseMapper.class);
        when(releaseMapper.releaseMappingAllowed(anyString())).thenReturn(true);
        when(releaseMapper.releaseMappingAllowed(anyString(), anyString())).thenReturn(true);
        // stagingResourceResolver = new StagingResourceResolver(context.getService(ResourceResolverFactory.class), context
        // .resourceResolver(), RELEASED, releaseMapper);
    }


    public static Matcher<? super Resource> exists() {
        return new CustomMatcher<Resource>("Resource should exist") {
            @Override
            public boolean matches(Object item) {
                Resource resource = (Resource) item;
                return null != resource && !ResourceUtil.isNonExistingResource(resource) &&
                        ResourceHandle.use(resource).isValid();
            }

        };
    }

    public static Matcher<? super Resource> existsInclusiveParents() {
        return new CustomMatcher<Resource>("Resource should exist") {
            @Override
            public boolean matches(Object item) {
                Resource resource = (Resource) item;
                boolean res = exists().matches(resource);
                if (res) { // also check parents.
                    while (null != resource && !"/".equals(resource.getPath())) {
                        Resource parent = resource.getParent();
                        assertThat("Parent should exist: " + parent, parent, exists());
                        Resource sameChild = parent.getChild(resource.getName());
                        assertThat("Parent should have resource as child: " + resource, sameChild, exists());
                        assertEquals(resource.getPath(), sameChild.getPath());
                        resource = parent;
                    }
                }
                return res;
            }
        };
    }


    // FIXME hps the attribute released will no longer be used
    protected String makeNode(ResourceBuilder builder, String documentName, String nodepath, boolean versioned,
                              boolean released, String title) throws RepositoryException {
        String[] mixins = versioned ? new String[]{TYPE_VERSIONABLE} : new String[]{};
        builder = builder.resource(documentName);
        builder = builder.resource(CONTENT_NODE, PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES, mixins);
        Resource contentResource = builder.getCurrentParent();
        Resource resource = builder.resource(nodepath, PROP_PRIMARY_TYPE, SELECTED_NODETYPE,
                PROP_MIXINTYPES, SELECTED_NODE_MIXINS).getCurrentParent();
        ResourceHandle handle = ResourceHandle.use(resource);
        if (null != title) {
            handle.setProperty(PROP_TITLE, title);
        }
        Calendar modificationTime = Calendar.getInstance();
        modificationTime.add(Calendar.DAY_OF_YEAR, -RandomUtils.nextInt(1, 90));
        modificationTime.add(Calendar.SECOND, -RandomUtils.nextInt(1, 1000));
        modificationTime.add(Calendar.MILLISECOND, RandomUtils.nextInt(1, 1000));
        handle.setProperty(PROP_LAST_MODIFIED, modificationTime);
        builder.commit();
        if (versioned) {
            Version version = versionManager.checkpoint(contentResource.getPath());
            if (released) {
                versionManager.getVersionHistory(contentResource.getPath())
                        .addVersionLabel(version.getName(), RELEASED, false);
            }
            builder.commit(); // unclear whether this is neccesary.
        }
        return resource.getPath();
    }
}
