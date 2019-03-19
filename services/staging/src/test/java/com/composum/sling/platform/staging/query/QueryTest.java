package com.composum.sling.platform.staging.query;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.platform.staging.AbstractStagingTest;
import com.composum.sling.platform.staging.StagingResourceResolver;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.query.QueryManager;
import java.io.IOException;
import java.util.*;

import static com.composum.sling.core.util.ResourceUtil.*;
import static com.composum.sling.platform.staging.query.Query.*;
import static java.util.Arrays.asList;
import static org.apache.jackrabbit.JcrConstants.JCR_CONTENT;
import static org.apache.sling.api.resource.ResourceUtil.getParent;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.when;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Tests for {@link StagingResourceResolver}.
 *
 * @author Hans-Peter Stoerr
 */
public class QueryTest extends AbstractStagingTest {

    private static final Logger LOG = getLogger(QueryTest.class);

    protected String folder;

    protected String document1;
    protected String node1version;
    protected String node1current;

    protected String document2;
    protected String node2oldandnew;
    protected String node2new;

    protected String unreleasedNode;
    protected String unversionedNode;
    protected QueryManager queryManager;
    protected String unreleasedDocument;
    protected String unversionedDocument;

    @Before
    public void setUpServices() throws Exception {
        final Session session = context.resourceResolver().adaptTo(Session.class);
        final Workspace workspace = session.getWorkspace();
        queryManager = workspace.getQueryManager();
        context.registerInjectActivateService(new QueryBuilderAdapterFactory());
    }

    @Before
    public void setUpContent() throws Exception {
        folder = "/folder";
        ResourceBuilder builderAtFolder = context.build().resource(folder).commit();
        document1 = folder + "/" + "document1";
        node1version = makeDocumentWithInternalNode(builderAtFolder, "document1", "n1/something", true, true, "3 third title", false);
        node1current = node1version.replaceAll("n1/something", "n1c/something");
        document2 = folder + "/" + "document2";
        node2oldandnew = makeDocumentWithInternalNode(builderAtFolder, "document2", "n2/some/kind/of/hierarchy/something", true,
                true, null, false);
        node2new = node2oldandnew.replaceAll("hierarchy", "current");
        context.build().resource(node2new, PROP_PRIMARY_TYPE, SELECTED_NODETYPE, PROP_MIXINTYPES,
                SELECTED_NODE_MIXINS).commit();
        unreleasedDocument = folder + "/" + "unreleasedDocument";
        unreleasedNode = makeDocumentWithInternalNode(builderAtFolder, "unreleasedDocument", "un/something", true, false, "4", false);
        unversionedDocument = folder + "/" + "unversionedDocument";
        unversionedNode = makeDocumentWithInternalNode(builderAtFolder, "unversionedDocument", "uv/something", false, false, "1 first " +
                "title", false);

        ResourceResolver resolver = context.resourceResolver();
        for (String path : new String[]{folder, node1version, document2, node2oldandnew, node2new, unreleasedNode,
                unversionedNode})
            assertNotNull(path + " doesn't exist", resolver.getResource(path));

        // some fresh changes
        context.build().resource(getParent(node1current)).commit();
        resolver.move(node1version, getParent(node1current));
        resolver.commit();
        assertNotNull(resolver.getResource(node1current));
        assertNull(resolver.getResource(node1version));
    }

    @Test
    public void findTopmostVersionedNodeByName() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element(PROP_JCR_CONTENT).orderBy(JcrConstants.JCR_CREATED);
        // TODO: this doesn't work right, but would be very hard to fix:
        // q.condition(q.conditionBuilder().name().eq().val(PROP_JCR_CONTENT));
        assertResults(q, document1 + "/" + PROP_JCR_CONTENT, document2 + "/" + PROP_JCR_CONTENT,
                unversionedDocument + "/" + PROP_JCR_CONTENT);
    }

    @Test
    public void examplesForConditions() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE).orderBy(PROP_TITLE).descending();
        assertResults(q, node2oldandnew, node1version, unversionedNode);

        QueryConditionDsl.QueryCondition condition = q.conditionBuilder().name().eq().val("something")
                .and().property(PROP_TITLE).eq().val("1 first title");
        q.condition(condition);
        assertResults(q, unversionedNode);

        condition = q.conditionBuilder().isNotNull(PROP_CREATED).and().startGroup()
                .upper().property(PROP_TITLE).eq().val("3 THIRD TITLE").or().contains(PROP_TITLE, "THIRD");
        q.condition(condition);
        assertResults(q, node1version);
    }

    @Test
    @Ignore("Only for development, as needed")
    public void printContent() throws Exception {
        printResourceRecursivelyAsJson(context.resourceResolver().getResource(folder));
    }

    @Test
    @Ignore("Only for development, as needed")
    public void printVersionStorage() throws Exception {
        printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
    }

    @Test
    public void findPathPrefix() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery().path(node2oldandnew);
        String prefix = q.searchpathForPathPrefixInVersionStorage(queryManager);
        // for example /jcr:system/jcr:versionStorage/73/ae/3b/73ae3bf3-c829-4b07-a217-fabe19b95a40/1.0/jcr
        // :frozenNode/n2/some/kind/of/hierarchy/something
        assertEquals("/jcr:system/jcr:versionStorage/X/X/X/X/1.0/jcr:frozenNode/n2/some/kind/of/hierarchy/something",
                prefix.replaceAll("/[a-f0-9-]{2,}", "/X"));
    }

    @Test
    public void queryBuilderOnPlainResolver() throws Exception {
        Query q = context.resourceResolver().adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE).orderBy(JcrConstants.JCR_CREATED);
        assertResults(q, node1current, node2oldandnew, node2new, unreleasedNode, unversionedNode);
    }

    @Test
    public void queryBuilder() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE).orderBy(JcrConstants.JCR_CREATED);
        assertResults(q, node2oldandnew, node1version, unversionedNode);
    }

    @Test
    public void limitsOnPlainResolver() throws Exception {
        Query q = context.resourceResolver().adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE).orderBy(COLUMN_PATH);
        assertResults(q, node1current, node2new, node2oldandnew, unreleasedNode, unversionedNode);

        q.offset(2);
        assertResults(q, node2oldandnew, unreleasedNode, unversionedNode);
        q.offset(2).limit(2);
        assertResults(q, node2oldandnew, unreleasedNode);
        q.offset(0).limit(2);
        assertResults(q, node1current, node2new);
    }

    @Test
    public void limitsOnStagingResolver() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE).orderBy(COLUMN_PATH);
        assertResults(q, node1version, node2oldandnew, unversionedNode);

        q.offset(1);
        assertResults(q, node2oldandnew, unversionedNode);
        q.limit(1).offset(1);
        assertResults(q, node2oldandnew);
        q.offset(0).limit(1);
        assertResults(q, node1version);
    }

    @Test
    public void selectAndExecute() throws RepositoryException {
        for (ResourceResolver resolver : Arrays.asList(context.resourceResolver(), this.stagingResourceResolver)) {
            LOG.info("Running with " + resolver);
            Query q = resolver.adaptTo(QueryBuilder.class).createQuery();
            q.path(folder).element("something").type(SELECTED_NODETYPE).orderBy(JcrConstants.JCR_CREATED);
            q.condition(q.conditionBuilder().contains(".").or().isNotNull(PROP_PRIMARY_TYPE));
            String[] selectedColumns = new String[]{"unused", PROP_CREATED, COLUMN_EXCERPT,
                    COLUMN_PATH, COLUMN_SCORE};
            Iterable<QueryValueMap> res = q.selectAndExecute(selectedColumns);
            int resultCount = 0;
            for (QueryValueMap valueMap : res) {
                resultCount++;
                Resource resource = valueMap.getResource();
                assertTrue(ResourceHandle.isValid(resource));

                for (String column : selectedColumns) {
                    Object objectValue = valueMap.get(column);
                    String stringValue = valueMap.get(column, String.class);
                    // contains doesn't work in oak-mock , just in jcr_mock, so some values are null,
                    // but accessing the results should work, anyway.
                    if ("unused".equals(column) || COLUMN_EXCERPT.equals(column) || COLUMN_SCORE.equals(column)) {
                        assertNull(column, objectValue);
                        assertNull(column, stringValue);
                    } else {
                        assertNotNull(column, objectValue);
                        assertTrue(column, StringUtils.isNotBlank(stringValue));
                    }
                }

                assertNotNull(valueMap.get(PROP_CREATED, Calendar.class));
                assertNotNull(valueMap.get(PROP_CREATED, Date.class));
                assertEquals(resource.getPath(), valueMap.get(COLUMN_PATH, String.class));
            }
            assertTrue(resultCount > 0);
        }
    }

    @Test
    public void orderBy() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").orderBy(PROP_TITLE).ascending();
        List<Resource> results = IterableUtils.toList(q.execute());
        List<String> resultPaths = new ArrayList<>();
        for (Resource r : results) resultPaths.add(r.getPath());
        assertThat("Wrong order of " + resultPaths, resultPaths.toArray(new String[0]),
                arrayContaining(node2oldandnew, unversionedNode, node1version));

        q.descending();
        results = IterableUtils.toList(q.execute());
        resultPaths.clear();
        for (Resource r : results) resultPaths.add(r.getPath());
        assertThat("Wrong order of " + resultPaths, resultPaths.toArray(new String[0]),
                arrayContaining(node1version, unversionedNode, node2oldandnew));
    }

    @Test
    public void queryBuilderWithIntermediatePath() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(getParent(node2oldandnew, 3));
        q.element("something").type(SELECTED_NODETYPE);
        assertResults(q, node2oldandnew);
    }

    @Test
    public void effectsOfReleaseMapper() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something");

        Mockito.reset(releaseMapper);
        // only checked in release from document2; only current from document1; unreleased has no release -> not there
        when(releaseMapper.releaseMappingAllowed(Mockito.startsWith(unreleasedDocument))).thenReturn(true);
        when(releaseMapper.releaseMappingAllowed(Mockito.startsWith(document1))).thenReturn(false);
        when(releaseMapper.releaseMappingAllowed(Mockito.startsWith(document2))).thenReturn(true);
        assertResults(q, node2oldandnew, node1current, unversionedNode);

        Mockito.reset(releaseMapper);
        // only checked in release from document1; only current from document2 and unreleasedDocument
        when(releaseMapper.releaseMappingAllowed(Mockito.startsWith(unreleasedDocument))).thenReturn(false);
        when(releaseMapper.releaseMappingAllowed(Mockito.startsWith(document1))).thenReturn(true);
        when(releaseMapper.releaseMappingAllowed(Mockito.startsWith(document2))).thenReturn(false);
        assertResults(q, node2oldandnew, node2new, node1version, unversionedNode, unreleasedNode);
    }

    @Test
    public void findByType() throws RepositoryException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path("/jcr:system/jcr:nodeTypes").type("rep:NodeType");
        List<Resource> result = IterableUtils.toList(q.execute());
        assertEquals(81, result.size());
        /* List<String> nodetypenames = new ArrayList<>();
        for (Resource r : result) nodetypenames.add(r.getName());
        Collections.sort(nodetypenames);
        LOG.debug("Nodetypes: {}", nodetypenames); */

        q.type("rep:MergeConflict");
        assertEquals(0, IterableUtils.size(q.execute()));

        q = context.resourceResolver().adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE);
        assertResults(q, node1current, node2oldandnew, node2new, unversionedNode, unreleasedNode);

        q.type(TYPE_TITLE); // a mixin of the nodes
        assertEquals(5, IterableUtils.size(q.execute()));

        q.type("nt:base"); // supertype
        assertEquals(5, IterableUtils.size(q.execute()));

        q.type("rep:MergeConflict"); // nonsense
        assertEquals(0, IterableUtils.size(q.execute()));


        q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE);
        assertResults(q, node1version, node2oldandnew, unversionedNode);

        q.type("rep:MergeConflict");
        assertEquals(0, IterableUtils.size(q.execute()));

        q.type("nt:base"); // supertype
        assertEquals(3, IterableUtils.size(q.execute()));

        q.type(TYPE_TITLE); // a mixin of the nodes
        assertEquals(3, IterableUtils.size(q.execute()));
    }

    @Test
    public void joinVersioned() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        QueryConditionDsl.QueryCondition join = q.joinConditionBuilder().isNotNull(PROP_CREATED);
        q.path(folder).element(PROP_JCR_CONTENT).type(TYPE_UNSTRUCTURED).orderBy(JcrConstants.JCR_CREATED);
        q.join(JoinType.Inner, JoinCondition.Descendant, SELECTED_NODETYPE, join);
        assertResults(q, document1 + "/" + PROP_JCR_CONTENT, document2 + "/" + PROP_JCR_CONTENT,
                unversionedDocument + "/" + PROP_JCR_CONTENT);
    }

    @Test
    public void joinUnversioned() throws RepositoryException, IOException {
        Query q = context.resourceResolver().adaptTo(QueryBuilder.class).createQuery();
        QueryConditionDsl.QueryCondition join = q.joinConditionBuilder().isNotNull(PROP_CREATED);
        q.path(folder).element(PROP_JCR_CONTENT).type(TYPE_UNSTRUCTURED).orderBy(JcrConstants.JCR_CREATED);
        q.join(JoinType.Inner, JoinCondition.Descendant, SELECTED_NODETYPE, join);
        assertResults(q, document1 + "/" + PROP_JCR_CONTENT,
                document2 + "/" + PROP_JCR_CONTENT, document2 + "/" + PROP_JCR_CONTENT, // twice due to join
                unversionedDocument + "/" + PROP_JCR_CONTENT, unreleasedDocument + "/" + PROP_JCR_CONTENT);
    }

    @Test
    public void joinWithSelects() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        QueryConditionDsl.QueryCondition join = q.joinConditionBuilder().isNotNull(PROP_CREATED);
        q.path(folder).element(PROP_JCR_CONTENT).type(TYPE_UNSTRUCTURED).orderBy(JcrConstants.JCR_CREATED);
        q.join(JoinType.Inner, JoinCondition.Descendant, SELECTED_NODETYPE, join);

        String joinPathSelector = join.joinSelector(COLUMN_PATH);
        String joinCreatedSelector = join.joinSelector(JcrConstants.JCR_CREATED);
        String[] selectedColumns = new String[]{COLUMN_PATH, joinPathSelector, joinCreatedSelector};
        Iterable<QueryValueMap> res = q.selectAndExecute(selectedColumns);

        int resultCount = 0;
        for (QueryValueMap valueMap : res) {
            resultCount++;
            Resource resource = valueMap.getResource();
            assertTrue(ResourceHandle.isValid(resource));
            assertEquals(resource.getPath(), valueMap.get(COLUMN_PATH));
            assertFalse(valueMap.get(COLUMN_PATH, String.class).startsWith("/jcr:system"));
            assertEquals(JCR_CONTENT, resource.getName());

            assertNotNull(valueMap.get(joinCreatedSelector));

            String joinPath = valueMap.get(joinPathSelector, String.class);
            assertFalse(joinPath.startsWith("/jcr:system"));
            assertFalse(joinPath.endsWith(JCR_CONTENT));
            assertEquals(valueMap.getJoinResource(join.getSelector()).getPath(), joinPath);
        }
        assertEquals(3, resultCount);
    }


    protected void assertResults(Query q, String... expected) throws RepositoryException {
        assertResults(IterableUtils.toList(q.execute()), expected);
    }

    protected void assertResults(List<Resource> results, String... expected) {
        List<String> resultPaths = new ArrayList<>();
        for (Resource r : results) resultPaths.add(r.getPath());
        assertThat("In results: " + resultPaths, resultPaths, everyItem(isIn(asList(expected))));
        assertThat("In results: " + resultPaths, resultPaths, containsInAnyOrder(expected));
        assertEquals("In results: " + resultPaths, expected.length, results.size());
    }

}
