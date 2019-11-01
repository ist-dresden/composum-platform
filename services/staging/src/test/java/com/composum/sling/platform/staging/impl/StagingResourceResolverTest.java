package com.composum.sling.platform.staging.impl;

import com.composum.platform.commons.util.ExceptionUtil;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.ReleasedVersionable;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.JcrTestUtils;
import com.composum.sling.platform.testing.testutil.SlingMatchers;
import com.composum.sling.platform.testing.testutil.codegen.SlingAssertionCodeGenerator;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.version.Version;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.CoreConstants.MIX_LAST_MODIFIED;
import static com.composum.sling.core.util.ResourceUtil.CONTENT_NODE;
import static com.composum.sling.core.util.ResourceUtil.MIX_VERSIONABLE;
import static com.composum.sling.core.util.ResourceUtil.PROP_DESCRIPTION;
import static com.composum.sling.core.util.ResourceUtil.PROP_MIXINTYPES;
import static com.composum.sling.core.util.ResourceUtil.PROP_PRIMARY_TYPE;
import static com.composum.sling.core.util.ResourceUtil.PROP_TITLE;
import static com.composum.sling.core.util.ResourceUtil.TYPE_LAST_MODIFIED;
import static com.composum.sling.core.util.ResourceUtil.TYPE_SLING_FOLDER;
import static com.composum.sling.core.util.ResourceUtil.TYPE_SLING_ORDERED_FOLDER;
import static com.composum.sling.core.util.ResourceUtil.TYPE_TITLE;
import static com.composum.sling.core.util.ResourceUtil.TYPE_UNSTRUCTURED;
import static com.composum.sling.core.util.ResourceUtil.TYPE_VERSIONABLE;
import static com.composum.sling.platform.staging.StagingConstants.PROP_REPLICATED_VERSION;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_MIX_RELEASE_ROOT;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_MIX_REPLICATEDVERSIONABLE;
import static com.composum.sling.platform.testing.testutil.JcrTestUtils.array;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.allOf;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.arrayContaining;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.arrayContainingInAnyOrder;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.contains;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.containsInAnyOrder;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.emptyIterable;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.everyItem;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.hasMapSize;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.hasResourcePath;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.instanceOf;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.is;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.isA;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.iterableWithSize;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.iteratorWithSize;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.mappedMatches;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.notNullValue;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.nullValue;
import static com.composum.sling.platform.testing.testutil.SlingMatchers.stringMatchingPattern;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

/**
 * Tests for {@link StagingResourceResolver}.
 */
public class StagingResourceResolverTest extends AbstractStagingTest {

    private String folder;
    private String node1;
    private String document1;
    private String document2;
    private String node2;
    private String unreleasedNode;
    private String unversionedNode;
    private StagingReleaseManager.Release release;
    private String releasesNode;
    private ResourceBuilder builderAtFolder;

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures errorCollector = new ErrorCollectorAlwaysPrintingFailures()
            .onFailure(() -> {
                Thread.sleep(500); // wait for logging messages to be written
                JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/folder"));
                JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
                JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/var/composum"));
            });

    @Before
    public void setUpContent() throws Exception {
        folder = "/folder";
        builderAtFolder = context.build().resource(folder, PROP_PRIMARY_TYPE, TYPE_SLING_ORDERED_FOLDER,
                ResourceUtil.PROP_MIXINTYPES, array(TYPE_MIX_RELEASE_ROOT))
                .withIntermediatePrimaryType(TYPE_UNSTRUCTURED).commit();
        builderAtFolder.resource(ResourceUtil.CONTENT_NODE);
        node1 = makeNode(builderAtFolder, "document1", "n1/something", true, true, "n1");
        document1 = folder + "/" + "document1";
        document2 = folder + "/" + "document2";
        node2 = makeNode(builderAtFolder, "document2", "n2/some/kind/of/hierarchy/something", true, true, "n2");
        unreleasedNode = makeNode(builderAtFolder, "unreleasedDocument", "un/something", true, false, "un");
        unversionedNode = makeNode(builderAtFolder, "unversionedDocument", "uv/something", false, false, "uv");
        for (String path : new String[]{folder, node1, document2, node2, unreleasedNode, unversionedNode}) {
            assertNotNull(path + " doesn't exist", context.resourceResolver().getResource(path));
        }

        List<StagingReleaseManager.Release> releases = releaseManager.getReleases(builderAtFolder.commit().getCurrentParent());
        assertEquals(1, releases.size());
        release = releases.get(0);
        stagingResourceResolver = (StagingResourceResolver) releaseManager.getResolverForRelease(releases.get(0), releaseMapper, false);
        releasesNode = StagingConstants.RELEASE_ROOT_PATH + folder + '/' + StagingConstants.NODE_RELEASES;
        assertNotNull(context.resourceResolver().getResource(releasesNode));
    }


    /**
     * Test to check behaviour of versionStorage wrt. rights - doesn't work yet because that'd require a working login
     * process in the mock, which isn't there.
     */
    @Test
    @Ignore
    public void checkVersionStorePermissions() throws Exception {
        Session session = context.resourceResolver().adaptTo(Session.class);
        AccessControlUtils.denyAllToEveryone(session, document2);
        session.save();
        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource(document2));
        ResourceResolver anonRes = context.getService(ResourceResolverFactory.class).getResourceResolver(null);
        assertNotNull(anonRes.getResource(document1));
        assertNull(anonRes.getResource(node2));
    }

    @Test
    public void printSetup() throws Exception {
        System.out.println(ReflectionToStringBuilder.toString(this, ToStringStyle.MULTI_LINE_STYLE));
        assertNotNull(context.resourceResolver().getResource(folder));
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource(folder));
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
    }

    @Test
    public void printStagedResolver() throws Exception {
        Resource resource = stagingResourceResolver.getResource(folder);
        JcrTestUtils.printResourceRecursivelyAsJson(resource);
        assertNotNull(resource);
    }


    @SuppressWarnings("deprecation")
    @Test
    public void notReleaseMappedIsJustPassedThrough() throws PersistenceException {
        reset(releaseMapper);
        when(releaseMapper.releaseMappingAllowed(argThat(isA(String.class)))).thenReturn(false);
        when(releaseMapper.releaseMappingAllowed(argThat(isA(String.class)), argThat(isA(String.class)))).thenReturn(false);

        for (String path : new String[]{folder, document2, node1, node2, unversionedNode, unreleasedNode,
                node1 + "/" + PROP_PRIMARY_TYPE, unreleasedNode + "/" + PROP_PRIMARY_TYPE}) {
            assertThat(path, stagingResourceResolver.getResource(path), existsInclusiveParents());
        }

        // that deletes the document in JCR but not the version. If it's still there, something's broken.
        deleteInJcr(this.document2);
        assertThat(this.document2, stagingResourceResolver.getResource(this.document2), nullValue());
    }

    @Test
    public void checkDefaultAttributeBehaviourOnMove() throws Exception {
        Resource versionable = context.build().resource("/somewhere/node", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE)).commit().getCurrentParent();
        Version version = versionManager.checkpoint(versionable.getPath());
        context.resourceResolver().commit();
        String originaldefaultpath = version.getParent().getProperty("default").getString();
        assertNotNull(originaldefaultpath);
        context.build().resource("/movedaround");
        context.resourceResolver().move("/somewhere/node", "/movedaround");
        context.resourceResolver().commit();
        Version version2 = versionManager.checkpoint("/movedaround/node");
        assertEquals(version.getParent().getPath(), version2.getParent().getPath());
        String newdefaultpath = version2.getParent().getProperty("default").getString();
        assertEquals("/movedaround/node", newdefaultpath); // changes on move and fresh checkin.
    }

    protected void deleteInJcr(String... deletePaths) throws PersistenceException {
        for (String toDelete : deletePaths) {
            ResourceResolver resolver = context.resourceResolver();
            Resource resource = resolver.resolve(toDelete);
            resolver.delete(resource);
            resolver.commit();
            assertNull(resolver.getResource(toDelete));
        }
    }

    @Test
    public void unversionedAndUnreleasedDoesNotExist() {
        for (String path : new String[]{unreleasedNode, unversionedNode}) {
            Resource resource = stagingResourceResolver.getResource(path);
            assertThat(path, resource, nullValue());
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
        for (String path : new String[]{node1, node2, node1 + "/" + PROP_PRIMARY_TYPE}) {
            assertThat(path, stagingResourceResolver.getResource(path), existsInclusiveParents());
        }
    }

    @Test
    public void deactivatedFolderIsHidden() throws RepositoryException {
        assertThat(node2, stagingResourceResolver.getResource(document1), existsInclusiveParents());
        assertThat(node2, stagingResourceResolver.getResource(document1 + "/jcr:content"), existsInclusiveParents());

        String path = releasesNode + "/current/root/document1";
        Resource releaseCopyOfDocument1 = context.resourceResolver().getResource(path);
        assertNotNull(path, releaseCopyOfDocument1);
        // hide the document
        ResourceHandle.use(releaseCopyOfDocument1).setProperty(StagingConstants.PROP_DEACTIVATED, true);

        assertNull(stagingResourceResolver.getResource(document1));
        assertNull(stagingResourceResolver.getResource(document1 + "/jcr:content"));
    }

    @Test
    public void releasedSubnodeIsFoundEvenAfterDeletion() throws PersistenceException {
        deleteInJcr(ResourceUtil.getParent(node2));
        assertNull(context.resourceResolver().getResource(node2));
        assertThat(stagingResourceResolver.resolve(node2), existsInclusiveParents());
        assertThat(stagingResourceResolver.getResource(node2), existsInclusiveParents());
    }

    @Test
    public void releasedDocumentIsFoundEvenAfterDeletion() throws PersistenceException {
        deleteInJcr(document2);
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
    public void newFolderShadowsDeletedFolder() throws Exception {
        deleteInJcr(document2);
        String node2Recreated = makeNode(builderAtFolder, "document2", "n2/some/kind/of/hierarchy/something",
                true, false, "n2-recreated");

        Resource node2AsReleased = stagingResourceResolver.getResource(node2);
        assertThat(node2AsReleased, existsInclusiveParents());
        assertThat(stagingResourceResolver.resolve(node2), existsInclusiveParents());
        // that should be the old resource, not the fresh one.
        assertEquals("n2", ResourceHandle.use(node2AsReleased).getProperty(PROP_TITLE));
    }

    @Test
    public void childrenAreOnlyReleased() throws Exception {
        deleteInJcr(document1, document2);
        Resource folderResource = stagingResourceResolver.resolve(folder);
        // JcrTestUtils.printResourceRecursivelyAsJson(folderResource);
        checkChildren(folderResource);
        assertEquals(3, IterableUtils.size(folderResource.getChildren()));
        // unreleasedDocument is not contained in release, and thus not found.
        assertNull(folderResource.getChild("unreleasedDocument"));
    }

    private void checkChildren(Resource parent) {
        assertThat(parent, existsInclusiveParents());
        for (Resource child : parent.getChildren()) {
            checkChildren(child);
        }
    }

    @Test(expected = PersistenceException.class)
    public void releasedCannotBeDeleted() throws PersistenceException {
        stagingResourceResolver.delete(stagingResourceResolver.getResource(node2));
    }

    @Test(expected = UnsupportedRepositoryOperationException.class)
    public void releasedCannotBeModified() throws Exception {
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
    public void filteredChildren() {
        assertNotNull(context.resourceResolver().getResource(releasesNode));
        assertTrue(context.resourceResolver().getResource(releasesNode).listChildren().hasNext());
        assertNotNull(context.resourceResolver().getResource(releasesNode + "/current"));
    }

    @Test
    public void testSearchPathUsage() throws Exception {
        ResourceBuilder builderAtApps = context.build().resource("/apps/bla").commit();
        String atApps = makeNode(builderAtApps, "someatapps", "something", false, false, "at");
        Resource resource = stagingResourceResolver.getResource(atApps.substring(6));
        assertThat(resource, existsInclusiveParents());
        assertEquals(atApps, resource.getPath());
    }

    @Test
    public void deepRead() throws Exception {
        String relPath = SlingResourceUtil.relativePath(folder + "/..", node2);
        { // deep read works normally
            Resource top = context.resourceResolver().getResource(folder).getParent();
            errorCollector.checkThat(top.getValueMap().get(relPath + "/jcr:title"), equalTo("n2"));
        }
        { // and also here.
            deleteInJcr(document1, document2); // make sure we read from version space
            Resource top = stagingResourceResolver.getResource(folder).getParent();
            errorCollector.checkThat(top.getValueMap().get(relPath + "/jcr:title"), equalTo("n2"));
        }
    }

    /** Verifies that {@link StagingConstants#PROP_REPLICATED_VERSION} is there. */
    @Test
    public void cplReplicatedVersion() throws Exception {
        String pathDocument1Node = document1 + "/" + ResourceUtil.CONTENT_NODE;
        Resource stagedResource = stagingResourceResolver.getResource(pathDocument1Node);
        ReleasedVersionable releasedVersionable = releaseManager.findReleasedVersionable(release, pathDocument1Node);
        String versionUuid = releasedVersionable.getVersionUuid();

        // for StagedResource
        errorCollector.checkThat(versionUuid, notNullValue(String.class));
        ValueMap stagedValueMap = stagedResource.getValueMap();
        errorCollector.checkThat(stagedValueMap.get(PROP_REPLICATED_VERSION,
                String.class), is(versionUuid));
        errorCollector.checkThat(stagedValueMap.entrySet().stream()
                .anyMatch((e) -> PROP_REPLICATED_VERSION.equals(e.getKey())), is(true));
        errorCollector.checkThat((String[]) stagedValueMap.entrySet().stream()
                        .filter((e) -> PROP_MIXINTYPES.equals(e.getKey())).findFirst().get().getValue(),
                arrayContaining(MIX_VERSIONABLE, MIX_LAST_MODIFIED, TYPE_MIX_REPLICATEDVERSIONABLE));
        errorCollector.checkThat(stagedValueMap.get(PROP_MIXINTYPES, String[].class),
                arrayContaining(MIX_VERSIONABLE, MIX_LAST_MODIFIED, TYPE_MIX_REPLICATEDVERSIONABLE));

        // for JCR nodes
        Node node = stagedResource.adaptTo(Node.class);
        Property property = node.getProperty(PROP_REPLICATED_VERSION);
        errorCollector.checkThat(property.getString(), is(versionUuid));

        property = node.getProperty(PROP_MIXINTYPES);
        errorCollector.checkThat(property.isMultiple(), is(true));
        Value[] values = property.getValues();
        errorCollector.checkThat(values.length, is(3));
        errorCollector.checkThat(findProperty(node.getProperties(), PROP_MIXINTYPES).getValues().length, is(3));
        errorCollector.checkThat(values[0].getString(), is(MIX_VERSIONABLE));
        errorCollector.checkThat(values[1].getString(), is(MIX_LAST_MODIFIED));
        errorCollector.checkThat(values[2].getString(), is(TYPE_MIX_REPLICATEDVERSIONABLE));

        errorCollector.checkThat(findProperty(node.getProperties(), PROP_REPLICATED_VERSION), notNullValue());
        errorCollector.checkThat(findProperty(node.getProperties("cpl:*"), PROP_REPLICATED_VERSION), notNullValue());
        errorCollector.checkThat(findProperty(node.getProperties("nix"), PROP_REPLICATED_VERSION), nullValue());
        errorCollector.checkThat(findProperty(node.getProperties(new String[]{"cpl:*", "whatever"}), PROP_REPLICATED_VERSION),
                notNullValue());
        errorCollector.checkThat(findProperty(node.getProperties(new String[]{"nix", "whatever"}), PROP_REPLICATED_VERSION),
                nullValue());
    }

    protected Property findProperty(PropertyIterator propertyIterator, String propertyName) throws RepositoryException {
        while (propertyIterator.hasNext()) {
            Property property = propertyIterator.nextProperty();
            if (propertyName.equals(property.getName())) { return property; }
        }
        return null;
    }

    @Test
    public void testAdaptToJcrTypes() throws Exception {
        deleteInJcr(document1, document2); // make sure we read from version space

        List<Resource> resources = JcrTestUtils.ancestorsAndSelf(stagingResourceResolver.resolve(node1));
        errorCollector.checkThat(resources, allOf(Matchers.iterableWithSize(6), everyItem(instanceOf(StagingResource.class))));

        for (Resource r : resources) {
            Node n = r.adaptTo(Node.class);
            errorCollector.checkThat(r.toString(), n, allOf(
                    notNullValue(), instanceOf(FrozenNodeWrapper.class)
            ));
            if (n != null) {
                errorCollector.checkThat(r.toString(), n.getPath(), equalTo(r.getPath()));
                errorCollector.checkThat(r.toString(), n.getName(), equalTo(r.getName()));

                List<Resource> childResources = IteratorUtils.toList(r.listChildren());
                errorCollector.checkThat(r.toString(), childResources, everyItem(instanceOf(StagingResource.class)));

                List<Node> childNodes = IteratorUtils.toList(n.getNodes());
                errorCollector.checkThat(r.toString(), childNodes, everyItem(instanceOf(FrozenNodeWrapper.class)));

                errorCollector.checkThat(r.toString(), childResources.stream().map(Resource::getName).collect(Collectors.joining(",")),
                        equalTo(childNodes.stream().map(ExceptionUtil.sneakExceptions(Node::getName)).collect(Collectors.joining(","))));

                if (!StagingUtils.isRoot(r)) {
                    errorCollector.checkThat(r.toString(), n.getParent().getPath(), equalTo(r.getParent().getPath()));
                    errorCollector.checkThat(r.toString(), n.getParent().getName(), equalTo(r.getParent().getName()));
                }

                String realPrimaryType = r.getValueMap().get(PROP_PRIMARY_TYPE, String.class);
                Property primaryType = n.getProperty(PROP_PRIMARY_TYPE);
                errorCollector.checkThat(r.toString(), primaryType, notNullValue());
                errorCollector.checkThat(r.toString(), primaryType.getString(), equalTo(realPrimaryType));

                Resource primaryTypePropertyResource = r.getChild(PROP_PRIMARY_TYPE);
                primaryType = primaryTypePropertyResource.adaptTo(Property.class);
                errorCollector.checkThat(primaryTypePropertyResource.toString(), primaryType, notNullValue());
                errorCollector.checkThat(primaryTypePropertyResource.toString(), primaryType.getString(), equalTo(realPrimaryType));
                errorCollector.checkThat(primaryTypePropertyResource.toString(), primaryType.getName(), is(PROP_PRIMARY_TYPE));
            }
        }

        List<Property> props = IteratorUtils.<Property>toList(stagingResourceResolver.resolve(node1).adaptTo(Node.class).getProperties());
        props.sort(Comparator.comparing(ExceptionUtil.sneakExceptions(Property::getName)));
        errorCollector.checkThat(
                props.stream().map(ExceptionUtil.sneakExceptions(Property::getName)).collect(Collectors.joining(", ")),
                equalTo("jcr:created, jcr:createdBy, jcr:lastModified, jcr:lastModifiedBy, jcr:mixinTypes, jcr:primaryType, jcr:title, jcr:uuid, sling:resourceType"));
    }

    @Test
    public void testPrimaryType() throws RepositoryException {
        errorCollector.checkThat(ResourceUtil.getPrimaryType(context.resourceResolver().getResource(node1)), equalTo("rep:Unstructured"));
        errorCollector.checkThat(context.resourceResolver().getResource(node1).adaptTo(Node.class).isNodeType("rep:Unstructured"), equalTo(true));
        errorCollector.checkThat(ResourceUtil.getPrimaryType(stagingResourceResolver.getResource(node1)), equalTo("rep:Unstructured"));
        errorCollector.checkThat(stagingResourceResolver.getResource(node1).adaptTo(Node.class).isNodeType("rep:Unstructured"), equalTo(true));
        errorCollector.checkThat(ResourceUtil.getPrimaryType(context.resourceResolver().getResource(node1).getChild(PROP_PRIMARY_TYPE)), nullValue());
        errorCollector.checkThat(ResourceUtil.getPrimaryType(stagingResourceResolver.getResource(node1).getChild(PROP_PRIMARY_TYPE)), nullValue());
    }

    @Test
    public void resourceType() {
        errorCollector.checkThat(ResourceUtil.isResourceType(context.resourceResolver().getResource(folder), TYPE_SLING_ORDERED_FOLDER), equalTo(true));
        errorCollector.checkThat(ResourceUtil.isResourceType(stagingResourceResolver.getResource(folder), TYPE_SLING_ORDERED_FOLDER), equalTo(true));
        errorCollector.checkThat(ResourceUtil.isResourceType(context.resourceResolver().getResource(folder), TYPE_SLING_FOLDER), equalTo(true));
        errorCollector.checkThat(ResourceUtil.isResourceType(stagingResourceResolver.getResource(folder), TYPE_SLING_FOLDER), equalTo(true));
    }


    @Test
    public void checkFrozenValuemap() throws Exception {
        ResourceBuilder versionableResourceBuilder = context.build().resource("/versioned/node", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                PROP_MIXINTYPES, array(TYPE_VERSIONABLE, TYPE_TITLE, TYPE_LAST_MODIFIED), "foo", "bar", PROP_TITLE, "title");
        versionableResourceBuilder.resource("sub", "nix", "nux");
        Resource versionedNode = versionableResourceBuilder.commit().getCurrentParent();
        Version version = versionManager.checkin(versionedNode.getPath());
        Resource frozenNode = context.resourceResolver().resolve(version.getFrozenNode().getPath());

        // JcrTestUtils.printResourceRecursivelyAsJson(versionedNode);
        // JcrTestUtils.printResourceRecursivelyAsJson(frozenNode);
        StagingResourceValueMap vm = new StagingResourceValueMap(frozenNode);
        StagingResourceValueMap vmsub = new StagingResourceValueMap(frozenNode.getChild("sub"));

        // new SlingAssertionCodeGenerator("vm", vm).useErrorCollector().printAssertions().printMapAssertions();
        // new SlingAssertionCodeGenerator("vmsub", vmsub).useErrorCollector().printAssertions().printMapAssertions();

        errorCollector.checkThat(vm.get(PROP_MIXINTYPES, String[].class), arrayContainingInAnyOrder(TYPE_TITLE,
                TYPE_LAST_MODIFIED, MIX_VERSIONABLE, StagingConstants.TYPE_MIX_REPLICATEDVERSIONABLE));

        errorCollector.checkThat(vm.isEmpty(), is(false));
        errorCollector.checkThat(vm.keySet(), containsInAnyOrder(PROP_REPLICATED_VERSION, "jcr:lastModifiedBy",
                "jcr:lastModified", "foo", "jcr:uuid", "jcr:primaryType", "jcr:title", "jcr:mixinTypes"));
        errorCollector.checkThat("" + vm.entrySet(), vm.entrySet(), iterableWithSize(8));
        errorCollector.checkThat(vm.size(), is(8));
        errorCollector.checkThat(vm.values(), iterableWithSize(8));

        errorCollector.checkThat(vm.get("jcr:uuid"), stringMatchingPattern("[0-9a-f-]{36}"));
        errorCollector.checkThat(vm.get("foo"), is("bar"));
        errorCollector.checkThat(vm.get("jcr:title"), is("title"));
        errorCollector.checkThat(vm.get("jcr:lastModifiedBy"), is("admin"));
        errorCollector.checkThat(vm.get("jcr:lastModified"), instanceOf(java.util.Calendar.class));
        errorCollector.checkThat(vm.get("jcr:primaryType"), is("nt:unstructured"));
        errorCollector.checkThat((String[]) vm.get("jcr:mixinTypes"), arrayContainingInAnyOrder(TYPE_TITLE,
                TYPE_LAST_MODIFIED, MIX_VERSIONABLE, TYPE_MIX_REPLICATEDVERSIONABLE));
        errorCollector.checkThat(vm.get(PROP_REPLICATED_VERSION), is(version.getIdentifier()));

        errorCollector.checkThat(vmsub.isEmpty(), is(false));
        errorCollector.checkThat(vmsub.entrySet(), iterableWithSize(2));
        errorCollector.checkThat(vmsub.keySet(), contains("jcr:primaryType", "nix"));
        errorCollector.checkThat(vmsub.size(), is(2));
        errorCollector.checkThat(vmsub.values(), contains("nt:unstructured", "nux"));

        errorCollector.checkThat(vmsub.get("jcr:primaryType"), is("nt:unstructured"));
        errorCollector.checkThat(vmsub.get("nix"), is("nux"));
    }

    /**
     * This checks how much the doFullCheck succeeds for the normal resource resolver. There are some differences,
     * which you want to inspect from time to time.
     */
    @Test
    @Ignore
    public void fullCheckUnstaged() {
        doFullCheck(context.resourceResolver());
    }

    @Test
    public void fullCheckStaged() {
        doFullCheck(stagingResourceResolver);
    }

    /**
     * Prints the source for doFullCheck - to be included into that method.
     * This creates a check for almost all public resource properties from the JcrResourceResolver - then we
     * can check that the StagingResourceResolver does exactly the same.
     */
    @Test
    @Ignore
    public void generateFullcheckSource() {
        ResourceResolver resourceResolver = context.resourceResolver();
        List<String> paths = new ArrayList<>();
        Resource r = resourceResolver.getResource(node1);
        do {
            paths.add(r.getPath());
            paths.add(r.getPath() + "/jcr:primaryType"); // also check property resources
        } while ((r = r.getParent()) != null && r.getPath().startsWith(folder));
        paths.sort(Comparator.comparing((String p) -> "" + StringUtils.countMatches(p, "/") + p));
        for (String path : paths) {
            r = resourceResolver.getResource(path);
            System.out.println("\n        r = resourceResolver.getResource(\"" + path + "\");");
            System.out.println("        ec.checkThat(r.getPath(), r, existsInclusiveParents());");
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

        errorCollector.checkThat(r.getPath(), r.getChildren(), mappedMatches(SlingMatchers::resourcePaths, contains("/folder/jcr:content", "/folder/document1", "/folder/document2")));
        errorCollector.checkThat(r.getPath(), r.hasChildren(), is(true));
        errorCollector.checkThat(r.getPath(), r.listChildren(), iteratorWithSize(3));
        errorCollector.checkThat(r.getPath(), r.getName(), is("folder"));
        errorCollector.checkThat(r.getPath(), r.getParent(), hasResourcePath("/"));
        errorCollector.checkThat(r.getPath(), r.getPath(), is("/folder"));
        errorCollector.checkThat(r.getPath(), r.getResourceResolver(), notNullValue(ResourceResolver.class));
        errorCollector.checkThat(r.getPath(), r.getResourceSuperType(), nullValue());
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("sling:OrderedFolder"));
        errorCollector.checkThat(r.getPath(), r.getValueMap(), allOf(
                hasMapSize(2),
                SlingMatchers.hasEntryMatching(is("jcr:mixinTypes"), arrayContaining(is("cpl:releaseRoot"))),
                SlingMatchers.hasEntryMatching(is("jcr:primaryType"), is("sling:OrderedFolder"))
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
        errorCollector.checkThat(r.getPath(), r.getResourceType(), is("sling:OrderedFolder/jcr:primaryType"));
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
                hasMapSize(6),
                // SlingMatchers.hasEntryMatching(is("jcr:versionHistory"), stringMatchingPattern("[0-9a-f-]{36}")),
                // SlingMatchers.hasEntryMatching(is("jcr:predecessors"), arrayContaining(stringMatchingPattern("[0-9a-f-]{36}"))),
                // SlingMatchers.hasEntryMatching(is("jcr:isCheckedOut"), is(true)),
                // SlingMatchers.hasEntryMatching(is("jcr:baseVersion"), stringMatchingPattern("[0-9a-f-]{36}")),
                SlingMatchers.hasEntryMatching(is("jcr:mixinTypes"), arrayContainingInAnyOrder(is("mix:versionable"),
                        is("mix:lastModified"), is(TYPE_MIX_REPLICATEDVERSIONABLE))),
                SlingMatchers.hasEntryMatching(is("jcr:primaryType"), is("nt:unstructured")),
                SlingMatchers.<Object, String>hasEntryMatching(is("jcr:uuid"), stringMatchingPattern("[0-9a-f-]{36}")),
                SlingMatchers.hasEntryMatching(is("jcr:lastModifiedBy"), is("admin")),
                SlingMatchers.hasEntryMatching(is("jcr:lastModified"), instanceOf(java.util.Calendar.class)),
                SlingMatchers.hasEntryMatching(is(PROP_REPLICATED_VERSION), instanceOf(String.class))
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
                SlingMatchers.hasEntryMatching(is("jcr:mixinTypes"), arrayContainingInAnyOrder(is("mix:lastModified"), is("mix:created"), is("mix:title"))),
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
