package com.composum.sling.platform.staging.impl;

import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.JcrTestUtils;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.hamcrest.ResourceMatchers;
import org.apache.sling.resourcebuilder.api.ResourceBuilder;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.VersionManager;
import java.util.Collections;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertNotNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Tests for {@link VersionSelectResourceResolver}.
 */
public class VersionsSelectResourceResolverTest implements CoreConstants, StagingConstants {

    private static final Logger LOG = getLogger(VersionsSelectResourceResolverTest.class);

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures errorCollector = new ErrorCollectorAlwaysPrintingFailures()
            .onFailure(() -> {
                Thread.sleep(500); // wait for logging messages to be written
                JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/folder"));
                JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
            });

    private ResourceBuilder builderAtFolder;
    private String folder;
    private String document1;
    private String node1;
    private String node1moved;

    private String document1version1;
    private String document1version2;

    private String document2;
    private String document2moved;
    private String node2;
    private String node2moved;

    private String document2version1;

    private VersionManager versionManager;

    /**
     * document 1 moded node - version 1 : folder/document1/foo/n1 , version 2: folder/document1/bar/n1 ;
     * moved document 2 node - version 1 : folder/document2/n2 , version 2: folder/sub/document2/n2
     */
    @Before
    public void setUpContent() throws Exception {
        versionManager = context.resourceResolver().adaptTo(Session.class).getWorkspace().getVersionManager();
        ResourceResolver resolver = context.resourceResolver();

        String[] mixins = new String[]{TYPE_VERSIONABLE};

        folder = "/folder";
        builderAtFolder = context.build().resource(folder, PROP_PRIMARY_TYPE, TYPE_SLING_ORDERED_FOLDER);
        ResourceBuilder document1Builder = builderAtFolder.resource("document1", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES, mixins, "name", "doc1");
        this.document1 = document1Builder.getCurrentParent().getPath();
        node1 = document1Builder.resource("foo/n1", "title", "n1").commit().getCurrentParent().getPath();
        document1version1 = versionManager.checkpoint(document1).getIdentifier();

        node1moved = resolver.move(node1, builderAtFolder.resource(this.document1 + "/bar").getCurrentParent().getPath()).getPath();
        resolver.getResource(document1).adaptTo(ModifiableValueMap.class).put("name", "doc1m");
        resolver.getResource(node1moved).adaptTo(ModifiableValueMap.class).put("title", "n1m");
        resolver.commit();
        document1version2 = versionManager.checkpoint(this.document1).getIdentifier();
        resolver.getResource(document1).adaptTo(ModifiableValueMap.class).put("name", "doc1c");
        resolver.getResource(node1moved).adaptTo(ModifiableValueMap.class).put("title", "n1c");

        ResourceBuilder document2Builder = builderAtFolder.resource("document2", PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED, PROP_MIXINTYPES, mixins, "name", "doc2");
        this.document2 = document2Builder.getCurrentParent().getPath();
        node2 = document2Builder.resource("n2", "title", "n2").commit().getCurrentParent().getPath();
        document2version1 = versionManager.checkpoint(document2).getIdentifier();

        document2moved = resolver.move(document2, builderAtFolder.resource("sub").getCurrentParent().getPath()).getPath();
        node2moved = folder + "/document2moved";
        node2moved = document2moved + "/n2";
        resolver.commit();

        for (String path : new String[]{folder, node1moved, document2moved, node2moved})
            errorCollector.checkThat(resolver.getResource(path), ResourceMatchers.path(path));
    }

    @Test
    public void checkAttributes() throws PersistenceException, RepositoryException {
        VersionSelectResourceResolver resolver;
        resolver = new VersionSelectResourceResolver(context.resourceResolver(), false, document1version1);
        errorCollector.checkThat(resolver.getResource(document1).getValueMap().get("name"), Matchers.is("doc1"));
        errorCollector.checkThat(resolver.getResource(node1).getValueMap().get("title"), Matchers.is("n1"));

        resolver = new VersionSelectResourceResolver(context.resourceResolver(), false, document1version2);
        errorCollector.checkThat(resolver.getResource(document1).getValueMap().get("name"), Matchers.is("doc1m"));
        errorCollector.checkThat(resolver.getResource(node1moved).getValueMap().get("title"), Matchers.is("n1m"));

        // unrelated version -> takes current content for document 1
        resolver = new VersionSelectResourceResolver(context.resourceResolver(), false, document2version1);
        errorCollector.checkThat(resolver.getResource(document1).getValueMap().get("name"), Matchers.is("doc1c"));
        errorCollector.checkThat(resolver.getResource(node1moved).getValueMap().get("title"), Matchers.is("n1c"));
    }

    @Test
    public void currentContent() throws PersistenceException {
        VersionSelectResourceResolver resolver = new VersionSelectResourceResolver(context.resourceResolver(), false, Collections.emptyMap());
        for (String path : new String[]{folder, document1, node1moved, document2moved, node2moved})
            errorCollector.checkThat(resolver.getResource(path), ResourceMatchers.path(path));
        for (String path : new String[]{node1, document2, node2})
            errorCollector.checkThat(resolver.getResource(path), nullValue());

        context.resourceResolver().delete(context.resourceResolver().getResource(node1moved));
        context.resourceResolver().delete(context.resourceResolver().getResource(node2moved));
        context.resourceResolver().commit();
        resolver.refresh();

        for (String path : new String[]{folder, document2moved})
            errorCollector.checkThat(resolver.getResource(path), ResourceMatchers.path(path));
        for (String path : new String[]{node1, node1moved, document2, node2, node2moved})
            errorCollector.checkThat(path, resolver.getResource(path), nullValue());
    }

    @Test
    public void bothOldVersions() throws RepositoryException {
        VersionSelectResourceResolver resolver = new VersionSelectResourceResolver(context.resourceResolver(),
                false, document1version1, document2version1);
        // caution: document2 old version is available under the moved path document2, despite it was checked in elsewhere!
        for (String path : new String[]{folder, document1, node1, document2moved, node2moved})
            errorCollector.checkThat(resolver.getResource(path), ResourceMatchers.path(path));
        for (String path : new String[]{node1moved, document2, node2})
            errorCollector.checkThat(path, resolver.getResource(path), nullValue());
    }

    @Test(expected = RepositoryException.class)
    public void invalidVersionsYieldNoResource() throws RepositoryException {
        new VersionSelectResourceResolver(context.resourceResolver(), false, "68ad1630-ffff-ffff-ffff-db88f5e4a380");
    }


    @Test
    public void printSetup() throws Exception {
        System.out.println(ReflectionToStringBuilder.toString(this, ToStringStyle.MULTI_LINE_STYLE));
        assertNotNull(context.resourceResolver().getResource(folder));
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource(folder));
        JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/jcr:system/jcr:versionStorage"));
    }

}
