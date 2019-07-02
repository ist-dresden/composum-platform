package com.composum.sling.platform.staging.query.impl;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.platform.staging.StagingReleaseManager;
import com.composum.sling.platform.staging.impl.AbstractStagingTest;
import com.composum.sling.platform.staging.impl.StagingResourceResolver;
import com.composum.sling.platform.staging.query.Query;
import com.composum.sling.platform.staging.query.QueryBuilder;
import com.composum.sling.platform.staging.query.QueryConditionDsl;
import com.composum.sling.platform.staging.query.QueryValueMap;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.JcrTestUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.query.QueryManager;
import java.io.IOException;
import java.util.*;

import static com.composum.sling.core.util.CoreConstants.PROP_MIXINTYPES;
import static com.composum.sling.core.util.ResourceUtil.*;
import static com.composum.sling.platform.staging.StagingConstants.TYPE_MIX_RELEASE_ROOT;
import static com.composum.sling.platform.staging.query.Query.*;
import static com.composum.sling.platform.testing.testutil.JcrTestUtils.array;
import static java.util.Arrays.asList;
import static org.apache.jackrabbit.JcrConstants.JCR_CONTENT;
import static org.apache.sling.api.resource.ResourceUtil.getParent;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.when;
import static org.slf4j.LoggerFactory.getLogger;

public class QueryTest extends AbstractStagingTest {

    private static final Logger LOG = getLogger(QueryTest.class);

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures errorCollector = new ErrorCollectorAlwaysPrintingFailures();

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
        ResourceBuilder builderAtFolder = context.build().resource(folder, PROP_PRIMARY_TYPE, TYPE_SLING_ORDERED_FOLDER,
                PROP_MIXINTYPES, array(TYPE_MIX_RELEASE_ROOT)).commit();
        document1 = folder + "/" + "document1";
        node1version = makeNode(builderAtFolder, "document1", "n1/something", true, true, "3 third title");
        node1current = node1version.replaceAll("n1/something", "n1c/something");
        document2 = folder + "/" + "document2";
        node2oldandnew = makeNode(builderAtFolder, "document2", "n2/some/kind/of/hierarchy/something", true,
                true, null);
        node2new = node2oldandnew.replaceAll("hierarchy", "current");
        context.build().resource(node2new, PROP_PRIMARY_TYPE, SELECTED_NODETYPE, PROP_MIXINTYPES,
                SELECTED_NODE_MIXINS).commit();
        unreleasedDocument = folder + "/" + "unreleasedDocument";
        unreleasedNode = makeNode(builderAtFolder, "unreleasedDocument", "un/something", true, false, "4");
        unversionedDocument = folder + "/" + "unversionedDocument";
        unversionedNode = makeNode(builderAtFolder, "unversionedDocument", "uv/something", false, false, "1 first " +
                "title");

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

        List<StagingReleaseManager.Release> releases = releaseManager.getReleases(builderAtFolder.commit().getCurrentParent());
        errorCollector.checkThat(releases.size(), is(1));
        stagingResourceResolver = (StagingResourceResolver) releaseManager.getResolverForRelease(releases.get(0), releaseMapper, false);
    }

    @Test
    public void queryQuickCheck() throws Exception {
        StagingQueryImpl q = (StagingQueryImpl) stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element(PROP_JCR_CONTENT).orderBy(JcrConstants.JCR_CREATED);
        errorCollector.checkThat(q.buildSQL2(), is("SELECT n.[jcr:path] , n.[jcr:created] AS [query:orderBy] \n" +
                "FROM [nt:base] AS n \n" +
                "WHERE ISDESCENDANTNODE(n, '/folder') \n" +
                "AND ( ISDESCENDANTNODE(n, '/folder/jcr:content/cpl:releases/current') OR NOT ISDESCENDANTNODE(n, '/folder/jcr:content/cpl:releases') )\n" +
                "AND NAME(n) = 'jcr:content' ORDER BY n.[jcr:created] ASC \n"));

        q.path(folder + "/xyz");
        errorCollector.checkThat(q.buildSQL2(), is("SELECT n.[jcr:path] , n.[jcr:created] AS [query:orderBy] \n" +
                "FROM [nt:base] AS n \n" +
                "WHERE ISDESCENDANTNODE(n, '/folder/jcr:content/cpl:releases/current/root/xyz') \n" +
                "AND NAME(n) = 'jcr:content' ORDER BY n.[jcr:created] ASC \n"));

        errorCollector.checkThat(q.buildSQL2Version(), is("SELECT n.[jcr:path], version.[jcr:uuid] AS [query:versionUuid], n.[jcr:frozenPrimaryType] AS [query:type], n.[jcr:frozenMixinTypes] AS [query:mixin] , n.[jcr:created] AS [query:orderBy] \n" +
                "FROM [nt:versionHistory] AS history \n" +
                "INNER JOIN [nt:version] AS version ON ISCHILDNODE(version, history) \n" +
                "INNER JOIN [nt:versionLabels] AS labels ON version.[jcr:uuid] = labels.[composum-release-current] \n" +
                "INNER JOIN [nt:frozenNode] AS n ON ISDESCENDANTNODE(n, version) \n" +
                "WHERE ISDESCENDANTNODE(history, '/jcr:system/jcr:versionStorage') \n" +
                "AND history.[default] like '/folder/%' \n" +
                "AND (NAME(n) = 'jcr:content' OR (NAME(n) = 'jcr:frozenNode' AND history.default LIKE '%/jcr:content')) ORDER BY n.[jcr:created] ASC \n"));
        errorCollector.checkThat(q.buildSQL24SingleVersion("inside/path"), is("SELECT n.[jcr:path], version.[jcr:uuid] AS [query:versionUuid], n.[jcr:frozenPrimaryType] AS [query:type], n.[jcr:frozenMixinTypes] AS [query:mixin] , n.[jcr:created] AS [query:orderBy] \n" +
                "FROM [nt:frozenNode] AS n \n" +
                "INNER JOIN [nt:versionHistory] as history ON ISDESCENDANTNODE(n, history) \n" +
                "INNER JOIN [nt:version] AS version ON ISCHILDNODE(version, history) \n" +
                "WHERE ISDESCENDANTNODE(n, 'inside/path') \n" +
                "AND (NAME(n) = 'jcr:content' OR (NAME(n) = 'jcr:frozenNode' AND history.default LIKE '%/jcr:content')) ORDER BY n.[jcr:created] ASC \n"));
    }

    @Test
    public void findTopmostVersionedNodeByName() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path("/").element(PROP_JCR_CONTENT).type(TYPE_UNSTRUCTURED).orderBy(JcrConstants.JCR_CREATED);
        // TODO: this doesn't work right, but would be very hard to fix:
        // q.condition(q.conditionBuilder().name().eq().val(PROP_JCR_CONTENT));
        assertResults(q, folder + "/" + PROP_JCR_CONTENT, document1 + "/" + PROP_JCR_CONTENT, document2 + "/" + PROP_JCR_CONTENT);
    }

    @Test
    public void examplesForConditions() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE).orderBy(PROP_TITLE).descending();
        assertResults(q, node2oldandnew, node1version);

        QueryConditionDsl.QueryCondition condition = q.conditionBuilder().name().eq().val("something")
                .and().isNull(PROP_TITLE);
        q.condition(condition);
        assertResults(q, node2oldandnew);

        condition = q.conditionBuilder().isNotNull(PROP_CREATED).and().startGroup()
                .upper().property(PROP_TITLE).eq().val("3 THIRD TITLE").or().contains(PROP_TITLE, "THIRD");
        q.condition(condition);
        assertResults(q, node1version);
    }

    @Test
    @Ignore("Only for development, as needed")
    public void printContent() throws Exception {
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource(folder));
    }

    @Test
    @Ignore("Only for development, as needed")
    public void printVersionStorage() throws Exception {
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
    }

    @Test
    public void queryBuilderOnPlainResolver() throws Exception {
        Query q = context.resourceResolver().adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE).orderBy(JcrConstants.JCR_CREATED);
        assertResults(q, node1current, node2oldandnew, node2new, unreleasedNode, unversionedNode);
    }

    @Test
    public void findReleaseNode() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path("/").type(CoreConstants.TYPE_SLING_ORDERED_FOLDER).orderBy(JcrConstants.JCR_PRIMARYTYPE);
        assertResults(q, folder);
    }

    @Test
    public void findHierarchyNode() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("document1").type(NT_UNSTRUCTURED).orderBy(JcrConstants.JCR_PATH);
        assertResults(q, document1);
    }

    @Test
    public void findNodesInVersionStorage() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE).orderBy(JcrConstants.JCR_CREATED);
        assertResults(q, node2oldandnew, node1version);
    }

    @Test
    public void findNodeInSingleVersionable() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(getParent(node2oldandnew, 3));
        q.element("something").type(SELECTED_NODETYPE);
        assertResults(q, node2oldandnew);
    }

    @Test
    public void limitsOnPlainResolver() throws Exception {
        Query q = QueryBuilder.makeQuery(context.resourceResolver());
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
        Query q = QueryBuilder.makeQuery(stagingResourceResolver);
        q.path(folder).element("something").type(SELECTED_NODETYPE).orderBy(COLUMN_PATH);
        assertResults(q, node1version, node2oldandnew);

        q.offset(1);
        assertResults(q, node2oldandnew);
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
                errorCollector.checkThat(valueMap.get(COLUMN_PATH, String.class), is(resource.getPath()));
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
                arrayContaining(node2oldandnew, node1version));

        q.descending();
        results = IterableUtils.toList(q.execute());
        resultPaths.clear();
        for (Resource r : results) resultPaths.add(r.getPath());
        assertThat("Wrong order of " + resultPaths, resultPaths.toArray(new String[0]),
                arrayContaining(node1version, node2oldandnew));
    }

    @Test
    public void effectsOfReleaseMapper() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something");

        Mockito.reset(releaseMapper);
        // only checked in release from document2; only current from document1; unreleased has no release -> not there
        when(releaseMapper.releaseMappingAllowed(Mockito.anyString())).thenReturn(true);
        when(releaseMapper.releaseMappingAllowed(Mockito.startsWith(document1))).thenReturn(false);
        when(releaseMapper.releaseMappingAllowed(Mockito.startsWith(document2))).thenReturn(true);
        assertResults(q, node2oldandnew, node1current);

        Mockito.reset(releaseMapper);
        // only checked in release from document1; only current from document2 and unreleasedDocument
        when(releaseMapper.releaseMappingAllowed(Mockito.anyString())).thenReturn(true);
        when(releaseMapper.releaseMappingAllowed(Mockito.startsWith(document1))).thenReturn(true);
        when(releaseMapper.releaseMappingAllowed(Mockito.startsWith(document2))).thenReturn(false);
        assertResults(q, node2oldandnew, node2new, node1version);
    }

    @Test
    public void findByType() throws RepositoryException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path("/jcr:system/jcr:nodeTypes").type("rep:NodeType");
        List<Resource> result = IterableUtils.toList(q.execute());
        errorCollector.checkThat(result.size(), is(96));
    /* List<String> nodetypenames = new ArrayList<>();
        for (Resource r : result) nodetypenames.add(r.getName());
        Collections.sort(nodetypenames);
        LOG.debug("Nodetypes: {}", nodetypenames); */

        q.type("rep:MergeConflict");
        errorCollector.checkThat(IterableUtils.size(q.execute()), is(0));

        q = context.resourceResolver().adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE);
        assertResults(q, node1current, node2oldandnew, node2new, unversionedNode, unreleasedNode);

        q.type(TYPE_TITLE); // a mixin of the nodes
        errorCollector.checkThat(IterableUtils.size(q.execute()), is(5));

        q.type("nt:base"); // supertype
        errorCollector.checkThat(IterableUtils.size(q.execute()), is(5));

        q.type("rep:MergeConflict"); // nonsense
        errorCollector.checkThat(IterableUtils.size(q.execute()), is(0));


        q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE);
        assertResults(q, node1version, node2oldandnew);

        q.type("rep:MergeConflict");
        errorCollector.checkThat(IterableUtils.size(q.execute()), is(0));

        q.type("nt:base"); // supertype
        errorCollector.checkThat(IterableUtils.size(q.execute()), is(2));

        q.type(TYPE_TITLE); // a mixin of the nodes
        errorCollector.checkThat(IterableUtils.size(q.execute()), is(2));
    }


    @Test
    public void joinVersioned() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        QueryConditionDsl.QueryCondition join = q.joinConditionBuilder().isNotNull(PROP_CREATED);
        q.path(folder).element(PROP_JCR_CONTENT).type(TYPE_UNSTRUCTURED).orderBy(JcrConstants.JCR_CREATED);
        q.join(JoinType.Inner, JoinCondition.Descendant, SELECTED_NODETYPE, join);
        assertResults(q, document1 + "/" + PROP_JCR_CONTENT, document2 + "/" + PROP_JCR_CONTENT);
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
        q.path(folder).element(PROP_JCR_CONTENT).type(TYPE_UNSTRUCTURED).orderBy(JcrConstants.JCR_PATH);
        q.join(JoinType.Inner, JoinCondition.Descendant, SELECTED_NODETYPE, join);

        String joinPathSelector = join.joinSelector(COLUMN_PATH);
        String joinCreatedSelector = join.joinSelector(JcrConstants.JCR_CREATED);
        String[] selectedColumns = new String[]{COLUMN_PATH, joinPathSelector, joinCreatedSelector};
        Iterable<QueryValueMap> res = q.selectAndExecute(selectedColumns);

        List<String> results = new ArrayList<>();
        for (QueryValueMap valueMap : res) {
            Resource resource = valueMap.getResource();
            assertTrue(ResourceHandle.isValid(resource));
            results.add(resource.getPath());
            errorCollector.checkThat(valueMap.get(COLUMN_PATH), is(resource.getPath()));
            assertFalse(valueMap.get(COLUMN_PATH, String.class).startsWith("/jcr:system"));
            errorCollector.checkThat(resource.getName(), is(JCR_CONTENT));

            assertNotNull(valueMap.get(joinCreatedSelector));

            String joinPath = valueMap.get(joinPathSelector, String.class);
            assertFalse(joinPath.startsWith("/jcr:system"));
            assertFalse(joinPath.endsWith(JCR_CONTENT));
            errorCollector.checkThat(joinPath, is(valueMap.getJoinResource(join.getSelector()).getPath()));
            errorCollector.checkThat(stagingResourceResolver.getResource(joinPath).getValueMap().get(PROP_PRIMARY_TYPE),
                    is(SELECTED_NODETYPE));
        }
        errorCollector.checkThat(results, contains(document1 + "/" + PROP_JCR_CONTENT, document2 + "/" + PROP_JCR_CONTENT));
    }


    protected void assertResults(Query q, String... expected) throws RepositoryException {
        assertResults(IterableUtils.toList(q.execute()), expected);
    }

    protected void assertResults(List<Resource> results, String... expected) {
        List<String> resultPaths = new ArrayList<>();
        for (Resource r : results) resultPaths.add(r.getPath());
        assertThat("In results: " + resultPaths, resultPaths, everyItem(isIn(asList(expected))));
        assertThat("In results: " + resultPaths, resultPaths, containsInAnyOrder(expected));
        errorCollector.checkThat("In results: " + resultPaths, results.size(), is(expected.length));
    }

}
