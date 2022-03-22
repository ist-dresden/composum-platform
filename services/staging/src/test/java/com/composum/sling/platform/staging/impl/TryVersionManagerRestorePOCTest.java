package com.composum.sling.platform.staging.impl;


import static org.apache.jackrabbit.JcrConstants.JCR_MIXINTYPES;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_UUID;
import static org.apache.jackrabbit.JcrConstants.JCR_VERSIONHISTORY;
import static org.apache.jackrabbit.JcrConstants.MIX_REFERENCEABLE;
import static org.apache.jackrabbit.JcrConstants.MIX_VERSIONABLE;
import static org.apache.jackrabbit.JcrConstants.NT_UNSTRUCTURED;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import javax.jcr.InvalidItemStateException;
import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.composum.sling.platform.testing.testutil.ErrorCollectorAlwaysPrintingFailures;
import com.composum.sling.platform.testing.testutil.JcrTestUtils;

/**
 * This was intended as a proof of concept to show that it's possible to check out the same version history twice, which
 * would have been helpful to resolve the problem that version histories of discarded versionables which are nevertheless
 * in a composum platform release are not referenced by any checked out versionable, and thus currently ignored bu
 * oak-upgrade. But, well: it turned out that this doesn't work. We put in this test, anyway, to watch
 * whether the behaviour of OAK changes later.
 *
 * @see "https://issues.apache.org/jira/browse/OAK-9728"
 */
public class TryVersionManagerRestorePOCTest {

    // wee need JCR_OAK for the node type handling - check protected properties etc.
    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Rule
    public final ErrorCollectorAlwaysPrintingFailures ec = new ErrorCollectorAlwaysPrintingFailures().onFailure(this::printJcr);

    private VersionManager versionManager;
    private Resource document;
    private Resource child;
    private String childUuid;
    private Version documentVersion;
    private @NotNull ResourceResolver resolver;
    private Session session;

    @Before
    public void setup() throws RepositoryException, PersistenceException {
        resolver = context.resourceResolver();
        session = resolver.adaptTo(Session.class);
        versionManager = session.getWorkspace().getVersionManager();
        document = context.create().resource(context.uniqueRoot().content() + "/document",
                JCR_MIXINTYPES, new String[]{MIX_VERSIONABLE},
                "something", "anditsvalue");
        child = context.create().resource(document, "child",
                JCR_PRIMARYTYPE, NT_UNSTRUCTURED, JCR_MIXINTYPES, new String[]{MIX_REFERENCEABLE}, "thechild", "17");
        resolver.commit();
        documentVersion = versionManager.checkpoint(document.getPath());
        resolver.commit();
        childUuid = child.getValueMap().get(JCR_UUID, String.class);
        JcrTestUtils.printResourceRecursivelyAsJson(document);
        ec.checkThat(child, notNullValue());
        System.out.println("Original child uuid: " + childUuid);
    }

    @Test
    public void deleteAndRestore() throws RepositoryException, PersistenceException {
        resolver.delete(document);
        resolver.commit();
        String newpath = context.uniqueRoot().content() + "/restored";
        versionManager.restore(newpath, documentVersion, false);
        JcrTestUtils.printResourceRecursivelyAsJson(resolver, newpath);
        ec.checkThat(resolver.getResource(newpath + "/child").getValueMap().get(JCR_UUID), is(childUuid));
    }

    @Test
    public void deleteAndRestore2() throws RepositoryException, PersistenceException {
        resolver.delete(document);
        resolver.commit();
        String newpath = context.uniqueRoot().content() + "/restored";
        Node versionNode = resolver.adaptTo(Session.class).getNodeByIdentifier(documentVersion.getIdentifier());
        // Strangely, restore works differently when it gets the version name as version argument: it expects the path to exist.
        // So we have to retrieve the version first.
        versionManager.restore(newpath, (Version) versionNode, false);
        JcrTestUtils.printResourceRecursivelyAsJson(resolver, newpath);
        ec.checkThat(resolver.getResource(newpath + "/child").getValueMap().get(JCR_UUID), is(childUuid));
    }

    @Test(expected = ItemExistsException.class)
    public void restoreWhileOriginalStillExistsFailsIfNotRemoveExisting() throws RepositoryException, PersistenceException {
        String newpath = context.uniqueRoot().content() + "/restored";
        versionManager.restore(newpath, documentVersion, false);
    }

    /**
     * Perhaps that should work, but actually the version manager throws up with a javax.jcr.InvalidItemStateException: Item is stale
     */
    @Test(expected = InvalidItemStateException.class)
    public void restoreWhileOriginalStillExistsWithRemoveExisting() throws RepositoryException, PersistenceException {
        resolver.commit();
        resolver.revert();
        resolver.refresh();
        String newpath = context.uniqueRoot().content() + "/restored";
        versionManager.restore(newpath, documentVersion, true);
        // JcrTestUtils.printResourceRecursivelyAsJson(resolver, newpath);
        // One of those should work:
        // ec.checkThat(resolver.getResource(child.getPath()).getValueMap().get(JCR_UUID), Matchers.is(childUuid));
        // ec.checkThat(resolver.getResource(newpath + "/child").getValueMap().get(JCR_UUID), Matchers.is(childUuid));
    }

    @Test(expected = PersistenceException.class)
    public void copyDoesnNotWorkInTest() throws RepositoryException, PersistenceException {
        String newpath = context.create().resource(context.uniqueRoot().content() + "/copyTarget").getPath();
        Resource copy = resolver.copy(document.getPath(), newpath);
        // hopefully we could copy the node and delete what isn't necessary, but the copy itself does not work
        // resolver.commit();
        // JcrTestUtils.printResourceRecursivelyAsJson(resolver, newpath);
        // resolver.revert();
        // ec.checkThat(resolver.getResource(child.getPath()).getValueMap().get(JCR_UUID), Matchers.is(childUuid));
    }

    /**
     * Workspace.copy works, but creates a new versionhistory.
     */
    @Test
    public void copyWithSession() throws RepositoryException, PersistenceException {
        String newpath = context.create().resource(context.uniqueRoot().content() + "/copyTarget").getPath();
        session.getWorkspace().copy(document.getPath(), newpath);
        String newVersionHistory = resolver.getResource(newpath).getValueMap().get(JCR_VERSIONHISTORY, String.class);
        ec.checkThat(newVersionHistory, is(notNullValue()));
        ec.checkThat(document.getValueMap().get(JCR_VERSIONHISTORY, String.class), is(not(newVersionHistory)));
    }

    @After
    public void teardown() throws PersistenceException {
        printJcr();
    }

    protected void printJcr() {
        try {
            Thread.sleep(500); // wait for logging messages to be written
        } catch (InterruptedException e) { // haha.
            throw new RuntimeException(e);
        }
        JcrTestUtils.printResourceRecursivelyAsJson(resolver.getResource("/content"));
        JcrTestUtils.printResourceRecursivelyAsJson(resolver.getResource("/jcr:system/jcr:versionStorage"));
    }

}
