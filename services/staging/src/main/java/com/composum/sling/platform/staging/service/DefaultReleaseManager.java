package com.composum.sling.platform.staging.service;

import com.composum.sling.core.util.ResourceUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.LabelExistsVersionException;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import static com.composum.sling.platform.staging.StagingUtils.isVersionable;

@Component(
        label = "Default Release Manager",
        description = "manges release of versionable content",
        immediate = true
)
@Service
@Deprecated
public class DefaultReleaseManager implements ReleaseManager {

    // getReleases

    @Override
    public List<String> getReleases(final ResourceResolver resolver, final Iterable<String> rootPaths)
            throws RepositoryException {
        List<String> releases = new ArrayList<>();
        for (String rootPath : rootPaths) {
            getReleases(resolver, releases, rootPath);
        }
        return releases;
    }

    private void getReleases(final ResourceResolver resolver, final List<String> releases, final String rootPath)
            throws RepositoryException {
        if (StringUtils.isNotBlank(rootPath)) {
            Resource root = resolver.getResource(rootPath);
            getReleases(resolver, releases, root);
        }
    }

    private void getReleases(final ResourceResolver resolver, final List<String> releases, final Resource resource)
            throws RepositoryException {

        if (resource != null) {

            if (isVersionable(resource)) {

                String path = resource.getPath();
                final VersionManager versionManager = getVersionManager(resolver);
                final VersionHistory versionHistory = versionManager.getVersionHistory(path);
                final VersionIterator allVersions = versionHistory.getAllVersions();
                while (allVersions.hasNext()) {
                    final Version version = allVersions.nextVersion();
                    final String[] labels = versionHistory.getVersionLabels(version);
                    for (String label : labels) {
                        if (label.startsWith(RELEASE_LABEL_PREFIX) && !releases.contains(label)) {
                            releases.add(label);
                        }
                    }
                }

            } else {
                for (Resource child : resolver.getChildren(resource)) {
                    getReleases(resolver, releases, child);
                }
            }
        }
    }

    // createRelease

    @Override
    public void updateToRelease(final ResourceResolver resolver, final String sitePath, String releaseLabel)
            throws RepositoryException {
        final Resource siteResource = resolver.getResource(sitePath);
        // search for the youngest release
        final Resource releasesResource = siteResource.getChild("jcr:content/releases");
        final Iterable<Resource> allReleases = releasesResource.getChildren();
        Resource youngest = null;
        Calendar youngestCreated = new GregorianCalendar(1,0,1);
        for (Resource releaseResource : allReleases) {
            final Calendar created = releaseResource.getValueMap().get("jcr:created", Calendar.class);
            if (youngestCreated.before(created)) {
                youngestCreated = created;
                youngest = releaseResource;
            }
        }
        // find all released objects
        // and add new label
        if (youngest != null) {
            final VersionManager versionManager = getVersionManager(resolver);
            final String oldLabel = youngest.getValueMap().get("key", String.class);
            crawlAndAddLabel(versionManager, siteResource, adjustReleaseLabel(oldLabel), adjustReleaseLabel(releaseLabel));
        }
    }

    private void crawlAndAddLabel(VersionManager versionManager, Resource parent, String oldLabel, String releaseLabel) throws RepositoryException {
        if (parent != null) {
            final Iterable<Resource> children = parent.getChildren();
            for (Resource child : children) {
                if (isVersionable(child)) {
                    String path = child.getPath();
                    final VersionHistory versionHistory = versionManager.getVersionHistory(path);
                    try {
                        final Version versionByLabel = versionHistory.getVersionByLabel(oldLabel);
                        versionHistory.addVersionLabel(versionByLabel.getName(), releaseLabel, false);
                    } catch (LabelExistsVersionException leE) {
                        // ignore
                    } catch (VersionException vE) {
                        // not labeled -> ignore
                    }
                } else {
                    crawlAndAddLabel(versionManager, child, oldLabel, releaseLabel);
                }
            }
        }
    }

    @Override
    public void createRelease(final ResourceResolver resolver, final Iterable<String> rootPaths, String releaseLabel)
            throws RepositoryException {
        releaseLabel = adjustReleaseLabel(releaseLabel);
        for (String rootPath : rootPaths) {
            createRelease(resolver, rootPath, releaseLabel);
        }
    }

    private void createRelease(final ResourceResolver resolver, final String rootPath, String releaseLabel)
            throws RepositoryException {
        if (StringUtils.isNotBlank(rootPath)) {
            Resource root = resolver.getResource(rootPath);
            createRelease(resolver, root, releaseLabel);
            resolver.adaptTo(Session.class).save();
        }
    }

    private void createRelease(final ResourceResolver resolver, final Resource resource,
                               String releaseLabel)
            throws RepositoryException {

        if (resource != null) {

            if (isVersionable(resource)) {

                String path = resource.getPath();
                final VersionManager versionManager = getVersionManager(resolver);
                final VersionHistory versionHistory = versionManager.getVersionHistory(path);
                final Version currentVersion = versionManager.getBaseVersion(path);
                if (!currentVersion.getName().equals("jcr:rootVersion")) {
                    versionHistory.addVersionLabel(currentVersion.getName(), releaseLabel, true);
                }
            } else {
                createRelease(resolver, resource.getChild(JcrConstants.JCR_CONTENT), releaseLabel);
            }
        }
    }

    // removeRelease

    @Override
    public void removeRelease(final ResourceResolver resolver, final Iterable<String> rootPaths,
                              String releaseLabel, boolean deleteVersions)
            throws RepositoryException {
        releaseLabel = adjustReleaseLabel(releaseLabel);
        for (String rootPath : rootPaths) {
            removeRelease(resolver, rootPath, releaseLabel, deleteVersions);
        }
    }

    private void removeRelease(final ResourceResolver resolver, final String rootPath,
                               String releaseLabel, boolean deleteVersions)
            throws RepositoryException {
        if (StringUtils.isNotBlank(rootPath)) {
            Resource root = resolver.getResource(rootPath);
            removeRelease(resolver, root, releaseLabel, deleteVersions);
        }
    }

    private void removeRelease(final ResourceResolver resolver, final Resource resource,
                               String releaseLabel, boolean deleteVersions)
            throws RepositoryException {

        if (resource != null) {

            if (isVersionable(resource)) {

                String path = resource.getPath();
                final VersionManager versionManager = getVersionManager(resolver);
                final VersionHistory versionHistory = versionManager.getVersionHistory(path);
                try {
                    final Version version = versionHistory.getVersionByLabel(releaseLabel);
                    if (deleteVersions) {
                        versionHistory.removeVersion(version.getName());
                    } else {
                        versionHistory.removeVersionLabel(releaseLabel);
                    }
                } catch (VersionException vex) {
                    // ok, not labelled with the requested release
                }

            } else {
                for (Resource child : resolver.getChildren(resource)) {
                    removeRelease(resolver, child, releaseLabel, deleteVersions);
                }
            }
        }
    }

    // purgeReleases

    @Override
    public void purgeReleases(final ResourceResolver resolver, final Iterable<String> rootPaths,
                              Calendar keepDate, int keepCount) {

    }

    // rollbackToRelease

    @Override
    public void rollbackToRelease(final ResourceResolver resolver, final Iterable<String> rootPaths,
                                  String releaseLabel) {

    }

    // helpers

    @CheckReturnValue
    public static boolean isCheckedOut(@Nonnull final Resource resource) throws RepositoryException {
        if (ResourceUtil.isNonExistingResource(resource)) {
            return false;
        }
        Node node = resource.adaptTo(Node.class);
        return node != null && isCheckedOut(node);
    }

    @CheckReturnValue
    public static boolean isCheckedOut(@Nonnull final Node node) throws RepositoryException {
        node.getPrimaryNodeType().isNodeType(ResourceUtil.TYPE_VERSIONABLE);
        return node.isCheckedOut();
    }

    protected String adjustReleaseLabel(String releaseLabel) {
        if (!releaseLabel.startsWith(RELEASE_LABEL_PREFIX)) {
            releaseLabel = RELEASE_LABEL_PREFIX + releaseLabel;
        }
        return releaseLabel;
    }

    @CheckReturnValue
    protected VersionManager getVersionManager(final ResourceResolver resolver) throws RepositoryException {
        final Session session = resolver.adaptTo(Session.class);
        final VersionManager versionManager = session.getWorkspace().getVersionManager();
        return versionManager;
    }
}
