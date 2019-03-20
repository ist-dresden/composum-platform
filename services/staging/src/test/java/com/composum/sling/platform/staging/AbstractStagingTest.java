package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.mapping.MappingRules;
import com.composum.sling.core.util.JsonUtil;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.service.ReleaseMapper;
import com.composum.sling.platform.staging.service.StagingCheckinPreprocessor;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.lang3.RandomUtils;
import org.apache.jackrabbit.api.JackrabbitSession;
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

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Calendar;

import static com.composum.sling.core.util.ResourceUtil.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
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
    protected ResourceResolver stagingResourceResolver;
    protected VersionManager versionManager;
    protected ReleaseMapper releaseMapper;

    @Before
    public final void setUpResolver() throws Exception {
        versionManager = context.resourceResolver().adaptTo(Session.class).getWorkspace().getVersionManager();

        releaseMapper = Mockito.mock(ReleaseMapper.class);
        when(releaseMapper.releaseMappingAllowed(anyString())).thenReturn(true);
        when(releaseMapper.releaseMappingAllowed(anyString(), anyString())).thenReturn(true);
        stagingResourceResolver = new StagingResourceResolver(context.getService(ResourceResolverFactory.class), context
                .resourceResolver(), RELEASED, releaseMapper);
    }


    /** Prints a resource and its subresources as JSON, depth effectively unlimited. */
    public static void printResourceRecursivelyAsJson(Resource resource) throws IOException, RepositoryException {
        StringWriter writer = new StringWriter();
        JsonWriter jsonWriter = new JsonWriter(writer);
        jsonWriter.setHtmlSafe(true);
        jsonWriter.setIndent("    ");
        JsonUtil.exportJson(jsonWriter, resource, MappingRules.getDefaultMappingRules(), 99);
        System.out.println(writer);
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


    /**
     * Creates a node at the given nodepath.
     *
     * @param builder      the builder that determines the path where the document is built
     * @param documentName resource name created at builder's folder
     * @param nodepath     a path of a subnode created below {documentName}/jcr:content
     * @param versioned    when true, we create a checkpoint for {documentName}/jcr:content
     * @param released     when true, we label the created version with {@link #RELEASED}
     * @param title        title of the document
     * @param storeorder   when true, the ordering of the parent's siblings is stored with the {@link com.composum.sling.platform.staging.service.StagingCheckinPreprocessor}.
     * @return the path of the node created at {nodepath} within the document
     */
    protected String makeDocumentWithInternalNode(ResourceBuilder builder, String documentName, String nodepath, boolean versioned,
                                                  boolean released, String title, boolean storeorder) throws RepositoryException {
        String[] mixins = versioned ? new String[]{TYPE_VERSIONABLE} : new String[]{};
        builder = builder.resource(documentName);
        builder = builder.resource(CONTENT_NODE, PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES, mixins);
        Resource contentResource = builder.getCurrentParent();
        builder.commit();

        if (versioned) { // create an empty first version
            if (storeorder) {
                JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
                new StagingCheckinPreprocessor().beforeCheckin(null, session, versionManager,
                        ResourceHandle.use(contentResource));
                builder.commit(); // since we used a session mock
            }
            versionManager.checkpoint(contentResource.getPath());
        }

        // create the node at nodepath
        Resource resource = builder.resource(nodepath, PROP_PRIMARY_TYPE, SELECTED_NODETYPE,
                PROP_MIXINTYPES, SELECTED_NODE_MIXINS).getCurrentParent();
        ResourceHandle handle = ResourceHandle.use(resource);
        if (null != title) {
            handle.setProperty(PROP_TITLE, title);
        }
        handle.setProperty(PROP_LAST_MODIFIED, randomTime());
        builder.commit();

        if (versioned) {
            if (storeorder) {
                JackrabbitSession session = Mockito.mock(JackrabbitSession.class);
                new StagingCheckinPreprocessor().beforeCheckin(null, session, versionManager,
                        ResourceHandle.use(contentResource));
                builder.commit(); // since we used a session mock
            }
            Version version = versionManager.checkpoint(contentResource.getPath());
            if (released) {
                versionManager.getVersionHistory(contentResource.getPath())
                        .addVersionLabel(version.getName(), RELEASED, false);
                builder.commit();
                versionManager.checkpoint(contentResource.getPath()); // don't let it just be the last version
            }
            builder.commit();
        }
        return resource.getPath();
    }

    private Calendar randomTime() {
        Calendar modificationTime = Calendar.getInstance();
        modificationTime.add(Calendar.DAY_OF_YEAR, -RandomUtils.nextInt(1, 90));
        modificationTime.add(Calendar.SECOND, -RandomUtils.nextInt(1, 1000));
        modificationTime.add(Calendar.MILLISECOND, RandomUtils.nextInt(1, 1000));
        return modificationTime;
    }
}
