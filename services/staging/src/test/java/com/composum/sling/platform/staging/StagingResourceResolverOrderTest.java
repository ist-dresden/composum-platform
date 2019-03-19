package com.composum.sling.platform.staging;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.service.ReleaseMapper;
import org.apache.commons.collections4.IterableUtils;
import org.apache.sling.api.resource.*;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.nodetype.ConstraintViolationException;
import java.io.IOException;

import static com.composum.sling.core.util.ResourceUtil.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Tests for {@link StagingResourceResolver}, especially for the ordering of documents, relying on the property
 * {@link com.composum.sling.platform.staging.service.StagingCheckinPreprocessor#PROP_SIBLINGSONCHECKIN}
 * that saves the ordering of the siblings of documents.
 */
public class StagingResourceResolverOrderTest extends AbstractStagingTest {

    private static final Logger LOG = getLogger(StagingResourceResolverOrderTest.class);

    private String folder;
    private String node1;
    private String document2;
    private String node2;
    private String unreleasedNode;
    private String unversionedNode;
    private ResourceBuilder builderAtFolder;

    @Before
    public void setUpContent() throws Exception {
        folder = "/folder";
        builderAtFolder = context.build().resource(folder).commit();
        node1 = makeDocumentWithInternalNode(builderAtFolder, "document1", "n1/something", true, true, "n1", true);
        document2 = folder + "/" + "document2";
        node2 = makeDocumentWithInternalNode(builderAtFolder, "document2", "n2/some/kind/of/hierarchy/something", true, true, "n2", true);
        unreleasedNode = makeDocumentWithInternalNode(builderAtFolder, "unreleasedDocument", "un/something", true, false, "un", true);
        unversionedNode = makeDocumentWithInternalNode(builderAtFolder, "unversionedDocument", "uv/something", false, false, "uv", true);
        for (String path : new String[]{folder, node1, document2, node2, unreleasedNode, unversionedNode})
            assertNotNull(path + " doesn't exist", context.resourceResolver().getResource(path));
        builderAtFolder.commit();
    }

    @Test
    public void checkSetup() throws Exception {
        assertNotNull(context.resourceResolver().getResource(folder));
        printResourceRecursivelyAsJson(context.resourceResolver().getResource(folder));
        printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
    }

    @Test
    public void notReleaseMappedIsJustPassedThrough() {
        ReleaseMapper releaseMapper = Mockito.mock(ReleaseMapper.class);
        when(releaseMapper.releaseMappingAllowed(anyString())).thenReturn(false);
        when(releaseMapper.releaseMappingAllowed(anyString(), anyString())).thenReturn(false);
        StagingResourceResolver resolver = new StagingResourceResolver(context.getService(ResourceResolverFactory
                .class), context.resourceResolver(), RELEASED, releaseMapper);

        for (String path : new String[]{folder, node1, node2, unversionedNode, unreleasedNode,
                node1 + "/" + PROP_PRIMARY_TYPE, unreleasedNode + "/" + PROP_PRIMARY_TYPE})
            assertThat(resolver.getResource(path), existsInclusiveParents());

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
            assertThat(stagingResourceResolver.getResource(path), existsInclusiveParents());
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
            assertThat(stagingResourceResolver.resolve(node2), exists());
            Resource res = stagingResourceResolver.getResource(node2);
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
        String node2Recreated = makeDocumentWithInternalNode(builderAtFolder, "document2", "n2/some/kind/of/hierarchy/something",
                true, false, "n2-recreated", true);

        Resource node2AsReleased = stagingResourceResolver.getResource(node2);
        assertThat(node2AsReleased, existsInclusiveParents());
        assertThat(stagingResourceResolver.resolve(node2), existsInclusiveParents());
        // that should be the old resource, not the fresh one.
        assertEquals("n2", ResourceHandle.use(node2AsReleased).getProperty(PROP_TITLE));
    }

    @Test
    public void childrenAreOnlyReleasedOrUnversioned() throws Exception {
        Resource folderResource = stagingResourceResolver.resolve(folder);
        printResourceRecursivelyAsJson(folderResource);
        checkChildren(folderResource);
        assertEquals(4, IterableUtils.size(folderResource.getChildren()));
        // jcr:content of unreleasedDocument is not contained in release, and thus not found.
        assertEquals(0, IterableUtils.size(folderResource.getChild("unreleasedDocument").getChildren()));

        StringBuilder buf = new StringBuilder();
        for (Resource child : folderResource.getChildren()) buf.append(child.getName()).append(" ");
        assertEquals("document1 document2 unreleasedDocument unversionedDocument ", buf.toString());
    }

    @Test
    @Ignore("Unimplemented yet")
    public void deletionOfCurrentResourcesDoesntChangeRelease() throws Exception {
        ResourceResolver resolver = context.resourceResolver();
        Resource resource = resolver.resolve(document2);
        resolver.delete(resource);
        resolver.commit();

        Resource folderResource = stagingResourceResolver.resolve(folder);
        printResourceRecursivelyAsJson(folderResource);
        checkChildren(folderResource);
        assertEquals(4, IterableUtils.size(folderResource.getChildren()));
        // jcr:content of unreleasedDocument is not contained in release, and thus not found.
        assertEquals(0, IterableUtils.size(folderResource.getChild("unreleasedDocument").getChildren()));

        StringBuilder buf = new StringBuilder();
        for (Resource child : folderResource.getChildren()) buf.append(child.getName()).append(" ");
        assertEquals("document1 document2 unreleasedDocument unversionedDocument ", buf.toString());
    }

    private void checkChildren(Resource parent) {
        assertThat(parent, existsInclusiveParents());
        for (Resource child : parent.getChildren()) {
            checkChildren(child);
        }
    }

    @Test(expected = StagingException.class)
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
        String atApps = makeDocumentWithInternalNode(builderAtApps, "someatapps", "something", true, true, "at", true);
        Resource resource = stagingResourceResolver.getResource(atApps.substring(6));
        assertThat(resource, existsInclusiveParents());
        assertEquals(atApps, resource.getPath());
    }

}
