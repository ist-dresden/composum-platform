package com.composum.sling.platform.staging.query;

import com.composum.sling.core.CoreAdapterFactory;
import com.composum.sling.platform.staging.AbstractStagingTest;
import com.composum.sling.platform.staging.StagingResourceResolver;
import org.apache.commons.collections4.IterableUtils;
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
import java.util.ArrayList;
import java.util.List;

import static com.composum.sling.core.util.ResourceUtil.*;
import static java.util.Arrays.*;
import static org.apache.sling.api.resource.ResourceUtil.getParent;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.when;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Tests for {@link StagingResourceResolver}.
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

    @Before
    public void setUpServices() throws Exception {
        final Session session = context.resourceResolver().adaptTo(Session.class);
        final Workspace workspace = session.getWorkspace();
        queryManager = workspace.getQueryManager();
        context.registerInjectActivateService(new QueryBuilderAdapterFactory());
        context.registerInjectActivateService(new CoreAdapterFactory());
    }

    @Before
    public void setUpContent() throws Exception {
        folder = "/folder";
        ResourceBuilder builderAtFolder = context.build().resource(folder).commit();
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
        assertEquals("/jcr:system/jcr:versionStorage/X/X/X/X/1.0/jcr:frozenNoX/n2/some/kind/of/hierarchy/something",
                prefix.replaceAll("[a-f0-9-]{2,}", "X"));
    }

    @Test
    public void queryBuilderOnPlainResolver() throws Exception {
        Query q = context.resourceResolver().adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE);
        assertResults(q, node1current, node2oldandnew, node2new, unreleasedNode, unversionedNode);
    }

    @Test
    public void queryBuilder() throws RepositoryException, IOException {
        Query q = stagingResourceResolver.adaptTo(QueryBuilder.class).createQuery();
        q.path(folder).element("something").type(SELECTED_NODETYPE);
        assertResults(q, node2oldandnew, node1version, unversionedNode);
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

    protected void assertResults(Query q, String... expected) throws RepositoryException {
        assertResults(IterableUtils.toList(q.execute()), expected);
    }

    protected void assertResults(List<Resource> results, String... expected) {
        List<String> resultPaths = new ArrayList<>();
        for (Resource r : results) resultPaths.add(r.getPath());
        assertThat("Result missing in " + resultPaths, asList(expected), everyItem(isIn(resultPaths)));
        assertThat("Unexpected result in " + resultPaths, resultPaths, everyItem(isIn(asList(expected))));
        assertEquals(expected.length, results.size());
    }

}