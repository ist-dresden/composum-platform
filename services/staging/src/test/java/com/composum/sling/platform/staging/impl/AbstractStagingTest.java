package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.ReleaseMapper;
import com.composum.sling.platform.staging.ReleasedVersionable;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.query.QueryBuilder;
import com.composum.sling.platform.staging.query.impl.QueryBuilderAdapterFactory;
import com.composum.sling.platform.testing.testutil.AnnotationWithDefaults;
import com.google.common.base.Function;
import org.apache.commons.lang3.RandomUtils;
import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.hamcrest.CustomMatcher;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.Mockito;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;
import java.io.InputStreamReader;
import java.util.Calendar;

import static com.composum.sling.core.util.ResourceUtil.*;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.satisfies;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

/**
 * Some common functionality for tests of the Staging functions.
 */
public abstract class AbstractStagingTest {

    /** Nodetype used for the specifically created inner nodes of versionables created with {@link #makeNode(ResourceBuilder, String, String, boolean, boolean, String)} (nodepath). */
    public static final String SELECTED_NODETYPE = "rep:Unstructured";
    public static final String[] SELECTED_NODE_MIXINS = {TYPE_CREATED, TYPE_LAST_MODIFIED, TYPE_TITLE};

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    protected static final String RELEASED = "theRelease";
    protected StagingResourceResolver stagingResourceResolver;
    protected VersionManager versionManager;
    protected ReleaseMapper releaseMapper;

    protected StagingReleaseManager releaseManager;
    protected StagingReleaseManager.Release currentRelease;

    @Before
    public final void setUpResolver() throws Exception {
        InputStreamReader cndReader = new InputStreamReader(getClass().getResourceAsStream("/testsetup/nodetypes.cnd"));
        NodeType[] nodeTypes = CndImporter.registerNodeTypes(cndReader, context.resourceResolver().adaptTo(Session.class));
        assertEquals(3, nodeTypes.length);

        versionManager = context.resourceResolver().adaptTo(Session.class).getWorkspace().getVersionManager();

        releaseManager = new DefaultStagingReleaseManager() {{
            configuration = AnnotationWithDefaults.of(DefaultStagingReleaseManager.Configuration.class);
        }};

        releaseMapper = Mockito.mock(ReleaseMapper.class);
        when(releaseMapper.releaseMappingAllowed(argThat(isA(String.class)))).thenReturn(true);
        // unused:
        when(releaseMapper.releaseMappingAllowed(argThat(isA(String.class)), argThat(isA(String.class)))).thenThrow(UnsupportedOperationException.class);

        context.registerAdapter(ResourceResolver.class, QueryBuilder.class,
                (Function<ResourceResolver, QueryBuilder>) (resolver) ->
                        new QueryBuilderAdapterFactory().getAdapter(resolver, QueryBuilder.class));
    }


    public static Matcher<? super Resource> exists() {
        return allOf(notNullValue(),
                not(satisfies(ResourceUtil::isNonExistingResource)),
                satisfies((r) -> ResourceHandle.use(r).isValid()),
                satisfies((r) -> r.adaptTo(Node.class) != null || r.adaptTo(Property.class) != null)
        );
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
                        assertEquals(resource.getValueMap().get(PROP_PRIMARY_TYPE), sameChild.getValueMap().get(PROP_PRIMARY_TYPE));
                        resource = parent;
                    }
                }
                return res;
            }
        };
    }

    protected String makeNode(ResourceBuilder builder, String documentName, String nodepath, boolean versioned,
                              boolean released, String title) throws RepositoryException, PersistenceException {
        String[] mixins = versioned ? new String[]{TYPE_VERSIONABLE, TYPE_LAST_MODIFIED} : new String[]{};
        builder = builder.resource(documentName, PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED);
        builder = builder.resource(CONTENT_NODE, PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES, mixins);
        Resource contentResource = builder.getCurrentParent();
        Resource resource = builder.resource(nodepath, PROP_PRIMARY_TYPE, SELECTED_NODETYPE,
                PROP_MIXINTYPES, SELECTED_NODE_MIXINS, PROP_RESOURCE_TYPE, "sling/" +
                        ResourceUtil.getName(nodepath) + "res").getCurrentParent();
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
                handle.setProperty("released", true);
                currentRelease = releaseManager.findRelease(handle, StagingConstants.CURRENT_RELEASE);
                builder.commit();
                releaseManager.updateRelease(currentRelease, ReleasedVersionable.forBaseVersion(contentResource));
            }
            handle.setProperty("versioned", true);
            builder.commit(); // unclear whether this is neccesary.
        }
        return resource.getPath();
    }
}
