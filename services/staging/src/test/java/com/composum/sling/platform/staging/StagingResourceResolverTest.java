package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.service.ReleaseMapper;
import com.composum.sling.platform.staging.service.StagingReleaseManager;
import com.composum.sling.platform.staging.testutil.JcrTestUtils;
import com.composum.sling.platform.staging.testutil.SlingAssertionCodeGenerator;
import com.composum.sling.platform.staging.testutil.SlingMatchers;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.commons.cnd.CndImporter;
import org.apache.sling.api.resource.*;
import org.apache.sling.hamcrest.ResourceMatchers;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.theories.suppliers.TestedOn;
import org.junit.rules.ErrorCollector;
import org.mockito.Mockito;
import org.slf4j.Logger;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeType;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

import static com.composum.sling.core.util.ResourceUtil.*;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_MIX_RELEASE_ROOT;
import static com.composum.sling.platform.staging.testutil.JcrTestUtils.array;
import static com.composum.sling.platform.staging.testutil.SlingMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Tests for {@link StagingResourceResolver}.
 */
public class StagingResourceResolverTest extends AbstractStagingTest {

    private static final Logger LOG = getLogger(StagingResourceResolverTest.class);

    private String folder;
    private String node1;
    private String document2;
    private String node2;
    private String unreleasedNode;
    private String unversionedNode;
    private ResourceBuilder builderAtFolder;

    @Rule
    public final ErrorCollector errorCollector = new ErrorCollector();

    @Before
    public void setUpContent() throws Exception {
        InputStreamReader cndReader = new InputStreamReader(getClass().getResourceAsStream("/testsetup/nodetypes.cnd"));
        NodeType[] nodeTypes = CndImporter.registerNodeTypes(cndReader, context.resourceResolver().adaptTo(Session.class));
        assertEquals(3, nodeTypes.length);

        folder = "/folder";
        builderAtFolder = context.build().resource(folder, ResourceUtil.PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                ResourceUtil.PROP_MIXINTYPES, array(TYPE_MIX_RELEASE_ROOT)).commit();
        node1 = makeNode(builderAtFolder, "document1", "n1/something", true, true, "n1");
        document2 = folder + "/" + "document2";
        node2 = makeNode(builderAtFolder, "document2", "n2/some/kind/of/hierarchy/something", true, true, "n2");
        releaseManager.updateCurrentReleaseFromWorkspace(builderAtFolder.commit().getCurrentParent());
        unreleasedNode = makeNode(builderAtFolder, "unreleasedDocument", "un/something", true, false, "un");
        unversionedNode = makeNode(builderAtFolder, "unversionedDocument", "uv/something", false, false, "uv");
        for (String path : new String[]{folder, node1, document2, node2, unreleasedNode, unversionedNode})
            assertNotNull(path + " doesn't exist", context.resourceResolver().getResource(path));

        List<StagingReleaseManager.Release> releases = releaseManager.getReleases(builderAtFolder.commit().getCurrentParent());
        assertEquals(1, releases.size());
        stagingResourceResolver = new StagingResourceResolverImpl(releases.get(0), context.resourceResolver(), releaseMapper, context.getService(ResourceResolverFactory.class));
    }

    @Test
    public void printSetup() throws Exception {
        assertNotNull(context.resourceResolver().getResource(folder));
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource(folder));
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
    }

    @Test
    public void notReleaseMappedIsJustPassedThrough() {
        ReleaseMapper releaseMapper = Mockito.mock(ReleaseMapper.class);
        when(releaseMapper.releaseMappingAllowed(anyString())).thenReturn(false);
        when(releaseMapper.releaseMappingAllowed(anyString(), anyString())).thenReturn(false);

        for (String path : new String[]{folder, node1, node2, unversionedNode, unreleasedNode,
                node1 + "/" + PROP_PRIMARY_TYPE, unreleasedNode + "/" + PROP_PRIMARY_TYPE})
            assertThat(path, stagingResourceResolver.getResource(path), existsInclusiveParents());

    }

    @Test
    public void unversionedIsReturned() {
        for (String path : new String[]{folder, unversionedNode}) {
            Resource resource = stagingResourceResolver.getResource(path);
            assertThat(resource, existsInclusiveParents());
        }
    }

    @Test
    public void notPresentYieldsNull() {
        assertNull(stagingResourceResolver.getResource("/this/does/not/exist"));
        assertTrue(stagingResourceResolver.resolve("/this/does/not/exist") instanceof NonExistingResource);
        assertNull(stagingResourceResolver.getResource(node1 + "/subpath/does/not/exist"));
        assertTrue(stagingResourceResolver.resolve(node1 + "/subpath/does/not/exist") instanceof NonExistingResource);
    }

    @Test
    public void releasedIsFound() {
        for (String path : new String[]{node1, node2, node1 + "/" + PROP_PRIMARY_TYPE})
            assertThat(path, stagingResourceResolver.getResource(path), existsInclusiveParents());
    }

    @Test
    public void releasedSubnodeIsFoundEvenAfterDeletion() throws PersistenceException {
        ResourceResolver resolver = context.resourceResolver();
        Resource resource = resolver.resolve(node2);
        resolver.delete(resource.getParent());
        resolver.commit();
        assertNull(resolver.getResource(node2));
        assertThat(stagingResourceResolver.resolve(node2), existsInclusiveParents());
        assertThat(stagingResourceResolver.getResource(node2), existsInclusiveParents());
    }

    @Test
    public void releasedDocumentIsFoundEvenAfterDeletion() throws PersistenceException {
        ResourceResolver resolver = context.resourceResolver();
        Resource resource = resolver.resolve(document2);
        resolver.delete(resource);
        resolver.commit();
        assertNull(resolver.getResource(document2));
        for (String path : new String[]{node2, node2 + "/" + PROP_PRIMARY_TYPE,
                document2 + "/" + CONTENT_NODE}) {
            assertThat(path, stagingResourceResolver.resolve(path), exists());
            Resource res = stagingResourceResolver.getResource(path);
            assertThat(res, exists());
            while (!res.getPath().equals("/")) {
                assertNotNull("No parent of " + res, res.getParent());
                res = res.getParent();
            }
        }
    }

    @Test
    public void newFolderShadowsDeletedFolder() throws IOException, RepositoryException {
        ResourceResolver resolver = context.resourceResolver();
        Resource resource = resolver.resolve(document2);
        resolver.delete(resource);
        resolver.commit();
        String node2Recreated = makeNode(builderAtFolder, "document2", "n2/some/kind/of/hierarchy/something",
                true, false, "n2-recreated");

        Resource node2AsReleased = stagingResourceResolver.getResource(node2);
        assertThat(node2AsReleased, existsInclusiveParents());
        assertThat(stagingResourceResolver.resolve(node2), existsInclusiveParents());
        // that should be the old resource, not the fresh one.
        assertEquals("n2", ResourceHandle.use(node2AsReleased).getProperty(PROP_TITLE));
    }

    @Test
    public void childrenAreOnlyReleasedOrUnversioned() throws Exception {
        Resource folderResource = stagingResourceResolver.resolve(folder);
        JcrTestUtils.printResourceRecursivelyAsJson(folderResource);
        checkChildren(folderResource);
        assertEquals(5, IterableUtils.size(folderResource.getChildren()));
        // jcr:content of unreleasedDocument is not contained in release, and thus not found.
        assertEquals(0, IterableUtils.size(folderResource.getChild("unreleasedDocument").getChildren()));
    }

    private void checkChildren(Resource parent) {
        assertThat(parent, existsInclusiveParents());
        for (Resource child : parent.getChildren()) {
            checkChildren(child);
        }
    }

    @Test(expected = UnsupportedOperationException.class)
    public void releasedCannotBeDeleted() throws PersistenceException {
        stagingResourceResolver.delete(stagingResourceResolver.getResource(node2));
    }

    @Test(expected = ConstraintViolationException.class)
    public void releasedCannotBeModified() throws PersistenceException, RepositoryException {
        ResourceHandle.use(stagingResourceResolver.getResource(node2)).setProperty(PROP_DESCRIPTION, "hallo");
    }

    @Test
    public void notExistingResource() {
        assertNull(stagingResourceResolver.getResource("/something/that/does/not/exist"));
        Resource resolved = stagingResourceResolver.resolve("/something/that/does/not/exist");
        assertNotNull(resolved);
        assertTrue(ResourceUtil.isNonExistingResource(resolved));
    }

    @Test
    public void testSearchPathUsage() throws Exception {
        ResourceBuilder builderAtApps = context.build().resource("/apps/bla").commit();
        String atApps = makeNode(builderAtApps, "someatapps", "something", true, true, "at");
        Resource resource = stagingResourceResolver.getResource(atApps.substring(6));
        assertThat(resource, existsInclusiveParents());
        assertEquals(atApps, resource.getPath());
    }

    @Test
    public void fullCheck() {
        doFullCheck(context.resourceResolver());
        doFullCheck(stagingResourceResolver);
    }

    /**
     * Prints the source for doFullCheck - to be included into that method.
     * This creates a check for almost all public resource properties from the JcrResourceResolver - then we
     * can check that the StagingResourceResolver does exactly the same.
     */
    @Test
    public void generateFullcheckSource() throws Exception {
        ResourceResolver resourceResolver = context.resourceResolver();
        List<String> paths = new ArrayList<>();
        Resource r = resourceResolver.getResource(node1);
        do {
            paths.add(r.getPath());
            paths.add(r.getPath() + "/jcr:primaryType"); // also check property resources
        } while ((r = r.getParent()) != null && r.getPath().startsWith(folder));
        Collections.sort(paths, Comparator.comparing((String p) -> "" + StringUtils.countMatches(p, "/") + p));
        for (String path : paths) {
            r = resourceResolver.getResource(path);
            System.out.println("\n        r = resourceResolver.getResource(\"" + path + "\");");
            System.out.println("        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());");
            new SlingAssertionCodeGenerator("r", r).useErrorCollector().withMessage("r.getPath()")
                    .ignoreProperties("getResourceMetadata", "toString").printAssertions();
        }
    }

    /**
     * Source generated by generateFullcheckSource. You need to replace UUID comparisons with containsString("-")
     * and calendar checks with
     */
    protected void doFullCheck(ResourceResolver resourceResolver) {
        Resource r;

        r = resourceResolver.getResource("/folder");
        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());

        errorCollector.checkThat(r.getPath(), r.getChildren(), mappedMatches(SlingMatchers::resourcePaths, contains("/folder/document1", "/folder/document2", "/folder/jcr:content", "/folder/unreleasedDocument", "/folder/unversionedDocument")));
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(true));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(5));
        errorCollector.checkThat(r.getPath(), r.getName(), is("folder"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("nt:unstructured"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), allOf(
                hasMapSize(2),
                SlingMatchers.hasEntryMatching(is("jcr:mixinTypes"), arrayContaining(is("cpl:releaseRoot"))),
                SlingMatchers.hasEntryMatching(is("jcr:primaryType"), is("nt:unstructured"))
        ));


        r = resourceResolver.getResource("/folder/document1");
        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());

        errorCollector.checkThat(r.getPath(), r.getChildren(), mappedMatches(SlingMatchers::resourcePaths, contains("/folder/document1/jcr:content")));
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(true));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(1));
        errorCollector.checkThat(r.getPath(), r.getName(), is("document1"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/folder"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder/document1"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("nt:unstructured"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), allOf(
                hasMapSize(1),
                SlingMatchers.hasEntryMatching(is("jcr:primaryType"), is("nt:unstructured"))
        ));


        r = resourceResolver.getResource("/folder/jcr:primaryType");
        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());

        errorCollector.checkThat(r.getPath(), r.getChildren(), emptyIterable());
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(false));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(0));
        errorCollector.checkThat(r.getPath(), r.getName(), is("jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/folder"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder/jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("nt:unstructured/jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), hasMapSize(0));


        r = resourceResolver.getResource("/folder/document1/jcr:content");
        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());

        errorCollector.checkThat(r.getPath(), r.getChildren(), mappedMatches(SlingMatchers::resourcePaths, contains("/folder/document1/jcr:content/n1")));
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(true));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(1));
        errorCollector.checkThat(r.getPath(), r.getName(), is("jcr:content"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/folder/document1"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder/document1/jcr:content"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("nt:unstructured"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), allOf(
                hasMapSize(7),
                SlingMatchers.hasEntryMatching(is("jcr:versionHistory"), stringMatchingPattern("[0-9a-f-]{36}")),
                SlingMatchers.hasEntryMatching(is("jcr:predecessors"), arrayContaining(stringMatchingPattern("[0-9a-f-]{36}"))),
                SlingMatchers.hasEntryMatching(is("jcr:isCheckedOut"), is(true)),
                SlingMatchers.hasEntryMatching(is("jcr:mixinTypes"), arrayContaining(is("mix:versionable"))),
                SlingMatchers.hasEntryMatching(is("jcr:primaryType"), is("nt:unstructured")),
                SlingMatchers.hasEntryMatching(is("jcr:uuid"), stringMatchingPattern("[0-9a-f-]{36}")),
                SlingMatchers.hasEntryMatching(is("jcr:baseVersion"), stringMatchingPattern("[0-9a-f-]{36}"))
        ));


        r = resourceResolver.getResource("/folder/document1/jcr:primaryType");
        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());

        errorCollector.checkThat(r.getPath(), r.getChildren(), emptyIterable());
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(false));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(0));
        errorCollector.checkThat(r.getPath(), r.getName(), is("jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/folder/document1"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder/document1/jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("nt:unstructured/jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), hasMapSize(0));


        r = resourceResolver.getResource("/folder/document1/jcr:content/jcr:primaryType");
        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());

        errorCollector.checkThat(r.getPath(), r.getChildren(), emptyIterable());
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(false));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(0));
        errorCollector.checkThat(r.getPath(), r.getName(), is("jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/folder/document1/jcr:content"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder/document1/jcr:content/jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("nt:unstructured/jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), hasMapSize(0));


        r = resourceResolver.getResource("/folder/document1/jcr:content/n1");
        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());

        errorCollector.checkThat(r.getPath(), r.getChildren(), mappedMatches(SlingMatchers::resourcePaths, contains("/folder/document1/jcr:content/n1/something")));
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(true));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(1));
        errorCollector.checkThat(r.getPath(), r.getName(), is("n1"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/folder/document1/jcr:content"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder/document1/jcr:content/n1"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("nt:unstructured"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), allOf(
                hasMapSize(1),
                SlingMatchers.hasEntryMatching(is("jcr:primaryType"), is("nt:unstructured"))
        ));


        r = resourceResolver.getResource("/folder/document1/jcr:content/n1/jcr:primaryType");
        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());

        errorCollector.checkThat(r.getPath(), r.getChildren(), emptyIterable());
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(false));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(0));
        errorCollector.checkThat(r.getPath(), r.getName(), is("jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/folder/document1/jcr:content/n1"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder/document1/jcr:content/n1/jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("nt:unstructured/jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), hasMapSize(0));


        r = resourceResolver.getResource("/folder/document1/jcr:content/n1/something");
        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());

        errorCollector.checkThat(r.getPath(), r.getChildren(), emptyIterable());
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(false));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(0));
        errorCollector.checkThat(r.getPath(), r.getName(), is("something"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/folder/document1/jcr:content/n1"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder/document1/jcr:content/n1/something"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("sling/somethingres"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), allOf(
                hasMapSize(8),
                SlingMatchers.hasEntryMatching(is("jcr:lastModifiedBy"), is("admin")),
                SlingMatchers.hasEntryMatching(is("jcr:created"), notNullValue(java.util.Calendar.class)),
                SlingMatchers.hasEntryMatching(is("jcr:mixinTypes"), arrayContaining(is("mix:lastModified"), is("mix:created"), is("mix:title"))),
                SlingMatchers.hasEntryMatching(is("jcr:lastModified"), notNullValue(java.util.Calendar.class)),
                SlingMatchers.hasEntryMatching(is("jcr:createdBy"), is("admin")),
                SlingMatchers.hasEntryMatching(is("sling:resourceType"), is("sling/somethingres")),
                SlingMatchers.hasEntryMatching(is("jcr:primaryType"), is("rep:Unstructured")),
                SlingMatchers.hasEntryMatching(is("jcr:title"), is("n1"))
        ));


        r = resourceResolver.getResource("/folder/document1/jcr:content/n1/something/jcr:primaryType");
        errorCollector.checkThat(r.getPath(), r, existsInclusiveParents());

        errorCollector.checkThat(r.getPath(), r.getChildren(), emptyIterable());
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(false));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(0));
        errorCollector.checkThat(r.getPath(), r.getName(), is("jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/folder/document1/jcr:content/n1/something"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder/document1/jcr:content/n1/something/jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("sling/somethingres/jcr:primaryType"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), hasMapSize(0));


    }

}
