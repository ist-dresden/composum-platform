package com.composum.sling.platform.staging.impl;

import com.composum.platform.commons.util.JcrIteratorUtil;
import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.CoreConstants;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.core.util.SlingResourceUtil;
import com.composum.sling.platform.staging.*;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy.Result;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.*;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionManager;
import java.util.*;
import java.util.stream.Collectors;

import static com.composum.sling.core.util.CoreConstants.*;
import static java.util.Objects.requireNonNull;
import static org.apache.jackrabbit.JcrConstants.JCR_LASTMODIFIED;

/**
 * Default implementation of {@link StagingReleaseManager} - description see there.
 *
 * @see StagingReleaseManager
 */
@Component(
        service = {StagingReleaseManager.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Composum Platform Staging Release Manager"
        }
)
@Designate(ocd = DefaultStagingReleaseManager.Configuration.class)
public class DefaultStagingReleaseManager implements StagingReleaseManager {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultStagingReleaseManager.class);

    /** Sub-path from the release root to the releases node. */
    static final String RELPATH_RELEASES_NODE = CONTENT_NODE + '/' + NODE_RELEASES;

    private final SiblingOrderUpdateStrategy siblingOrderUpdateStrategy = new SiblingOrderUpdateStrategy();

    @Reference
    protected ResourceResolverFactory resourceResolverFactory;

    protected Configuration configuration;

    @Activate
    @Deactivate
    @Modified
    public void updateConfig(Configuration configuration) {
        this.configuration = configuration;
    }

    @Nullable
    @Override
    public ResourceHandle findReleaseRoot(@Nonnull Resource resource) {
        ResourceHandle result = ResourceHandle.use(resource);
        while (result.isValid() && !result.isOfType(TYPE_MIX_RELEASE_ROOT))
            result = ResourceHandle.use(result.getParent());
        return result.isValid() ? result : null;
    }

    @Nonnull
    @Override
    public List<Release> getReleases(@Nonnull Resource resource) {
        List<Release> result = new ArrayList<>();
        ResourceHandle root = ResourceHandle.use(requireNonNull(findReleaseRoot(resource)));
        if (root.isValid()) {
            if (root.getChild(RELPATH_RELEASES_NODE + '/' + NODE_CURRENT_RELEASE) == null) {
                try { // implicitly create current release which should always be there.
                    ensureRelease(root, NODE_CURRENT_RELEASE);
                } catch (RepositoryException | PersistenceException e) {
                    LOG.error("Trouble creating current release for " + resource.getPath(), e);
                }
            }
            Resource releasesNode = root.getChild(RELPATH_RELEASES_NODE);
            if (releasesNode != null) {
                for (Resource releaseNode : releasesNode.getChildren()) {
                    ReleaseImpl release = new ReleaseImpl(root, releaseNode);
                    result.add(release);
                }
            }
        }
        result.sort(Comparator.comparing(Release::getNumber, ReleaseNumberCreator.COMPARATOR_RELEASES));
        return result;
    }

    @Nonnull
    @Override
    public Release findRelease(@Nonnull Resource resource, @Nonnull String releaseNumber) {
        for (Release release : getReleases(resource))
            if (release.getNumber().equals(releaseNumber)) return release;
        throw new ReleaseNotFoundException();
    }

    @Nonnull
    @Override
    public Release findReleaseByUuid(@Nonnull Resource resource, @Nonnull String releaseUuid) throws ReleaseNotFoundException {
        for (Release release : getReleases(resource))
            if (release.getUuid().equals(releaseUuid)) return release;
        throw new ReleaseNotFoundException();
    }

    @Nonnull
    @Override
    public Release createRelease(@Nonnull Resource resource, @Nonnull ReleaseNumberCreator releaseType) throws PersistenceException, RepositoryException {
        List<Release> releases = getReleases(resource);
        Release lastRelease = releases.stream()
                .max(Comparator.comparing(Release::getNumber, releaseType.releaseComparator()))
                .orElse(null);
        try {
            return createRelease(resource, lastRelease, releaseType);
        } catch (ReleaseExistsException e) { // that should be impossible.
            LOG.error("Bug: how can the release " + (lastRelease != null ? lastRelease.getNumber() : null) +
                    " exist for " + releaseType + " and releases "
                    + releases.stream().map(Release::getNumber).collect(Collectors.joining(", ")));
            throw new RepositoryException(e);
        }
    }

    @Nonnull
    @Override
    public Release createRelease(@Nonnull Release copyFromRelease, @Nonnull ReleaseNumberCreator releaseType) throws ReleaseExistsException, PersistenceException, RepositoryException {
        return createRelease(copyFromRelease.getReleaseRoot(), copyFromRelease, releaseType);
    }

    @Nonnull
    protected Release createRelease(@Nonnull Resource resource, @Nullable Release rawCopyFromRelease, @Nonnull ReleaseNumberCreator releaseType) throws ReleaseExistsException, ReleaseNotFoundException, PersistenceException, RepositoryException {
        Resource root = requireNonNull(findReleaseRoot(resource));
        ReleaseImpl copyFromRelease = ReleaseImpl.unwrap(rawCopyFromRelease);
        Optional<String> previousReleaseNumber;

        if (copyFromRelease != null) {
            previousReleaseNumber = Optional.of(copyFromRelease.getNumber());
        } else {
            previousReleaseNumber = getReleases(resource).stream().map(Release::getNumber).max(ReleaseNumberCreator.COMPARATOR_RELEASES);
        }
        String newReleaseNumber = previousReleaseNumber.map(releaseType::bumpRelease).orElse(releaseType.bumpRelease(""));

        try {
            findRelease(root, newReleaseNumber);
            throw new ReleaseExistsException(root, newReleaseNumber);
        } catch (ReleaseNotFoundException e) {
            // expected
        }

        ReleaseImpl newRelease = ensureRelease(root, newReleaseNumber);
        if (null != copyFromRelease) {
            new NodeTreeSynchronizer().update(copyFromRelease.getReleaseNode(), newRelease.getReleaseNode());
        }
        return newRelease;
    }

    @Nonnull
    @Override
    public List<ReleasedVersionable> listReleaseContents(@Nonnull Release rawRelease) {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        List<ReleasedVersionable> result = new ArrayList<>();
        Resource releaseWorkspaceCopy = requireNonNull(release.getReleaseNode().getChild(NODE_RELEASE_ROOT));
        String query = "/jcr:root" + releaseWorkspaceCopy.getPath() + "//element(*," + TYPE_VERSIONREFERENCE + ")";

        Iterator<Resource> versionReferences = release.getReleaseNode().getResourceResolver()
                .findResources(query, Query.XPATH);
        while (versionReferences.hasNext())
            result.add(ReleasedVersionable.fromVersionReference(releaseWorkspaceCopy, versionReferences.next()));
        return result;
    }

    @Override
    @Nonnull
    public Map<String, Result> updateRelease(@Nonnull Release rawRelease, @Nonnull ReleasedVersionable releasedVersionable) throws RepositoryException, PersistenceException {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        Resource releaseWorkspaceCopy = release.getWorkspaceCopyNode();
        String newPath = releaseWorkspaceCopy.getPath() + '/' + releasedVersionable.getRelativePath();

        String query = "/jcr:root" + releaseWorkspaceCopy.getPath() + "//element(*," + TYPE_VERSIONREFERENCE + ")"
                + "[@" + PROP_VERSIONABLEUUID + "='" + releasedVersionable.getVersionableUuid() + "']";
        Iterator<Resource> versionReferences = release.getReleaseNode().getResourceResolver()
                .findResources(query, Query.XPATH);
        Resource versionReference = versionReferences.hasNext() ? versionReferences.next() : null;

        if (versionReference == null) {
            versionReference = ResourceUtil.getOrCreateResource(release.getReleaseNode().getResourceResolver(), newPath,
                    TYPE_UNSTRUCTURED + '/' + TYPE_VERSIONREFERENCE);
        } else if (!versionReference.getPath().equals(newPath)) {
            Resource oldParent = versionReference.getParent();
            ResourceResolver resolver = versionReference.getResourceResolver();

            ResourceUtil.getOrCreateResource(resolver, ResourceUtil.getParent(newPath), TYPE_UNSTRUCTURED);
            resolver.move(versionReference.getPath(), ResourceUtil.getParent(newPath));
            versionReference = resolver.getResource(newPath);

            cleanupOrphans(releaseWorkspaceCopy.getPath(), oldParent);
        }

        updateReleaseLabel(release, releasedVersionable);

        releasedVersionable.writeToVersionReference(requireNonNull(versionReference));

        release.updateLastModified();

        return updateParents(release, releasedVersionable);
    }

    /** Sets the label {@link StagingConstants#RELEASE_LABEL_PREFIX}-{releasenumber} on the version the releasedVersionable refers to. */
    protected void updateReleaseLabel(ReleaseImpl release, ReleasedVersionable releasedVersionable) throws RepositoryException {
        Session session = release.getReleaseRoot().getResourceResolver().adaptTo(Session.class);
        if (session == null) // impossible.
            throw new RepositoryException("No session for " + release.getReleaseRoot().getPath());

        VersionManager versionManager = session.getWorkspace().getVersionManager();
        VersionHistory versionHistory = versionManager.getVersionHistory(release.getReleaseRoot().getPath() + '/' + releasedVersionable.getRelativePath());
        String label = release.getReleaseLabel();

        if (StringUtils.isBlank(releasedVersionable.getVersionUuid())) {
            try {
                versionHistory.removeVersionLabel(label);
            } catch (VersionException e) {
                LOG.debug("Label {} wasn't set - OK.", label);
            }
            return;
        }

        for (Version version : JcrIteratorUtil.asIterable(versionHistory.getAllVersions())) {
            if (releasedVersionable.getVersionUuid().equals(version.getIdentifier())) {
                versionHistory.addVersionLabel(version.getName(), label, true);
                LOG.debug("Setting label {} on version {}", label, version.getIdentifier());
                return;
            }
        }

        throw new IllegalArgumentException("Version not found for " + releasedVersionable + " in release " + release);
    }

    @Nonnull
    @Override
    public Map<String, Result> updateRelease(@Nonnull Release release, @Nonnull List<ReleasedVersionable> releasedVersionableList) throws RepositoryException, PersistenceException {
        Map<String, Result> result = new TreeMap<>();
        for (ReleasedVersionable releasedVersionable : releasedVersionableList) {
            Map<String, Result> partialResult = updateRelease(release, releasedVersionable);
            for (Map.Entry<String, Result> entry : partialResult.entrySet()) {
                Result oldResult = result.get(entry.getKey());
                Result combinedResult = Result.max(oldResult, entry.getValue());
                result.put(entry.getKey(), combinedResult);
            }
        }
        return result;
    }

    @Override
    public void removeRelease(@Nonnull Release rawRelease) throws PersistenceException {
        ReleaseImpl release = requireNonNull(ReleaseImpl.unwrap(rawRelease));
        LOG.info("removeRelease {}", release);
        Resource releaseNode = release.getReleaseNode();
        releaseNode.getResourceResolver().delete(releaseNode);
    }

    /**
     * Goes through all parents of the version reference, sets their attributes from the working copy
     * and fixes the node ordering if necessary.
     * @return a map with paths where we changed the order of children in the release.
     */
    @Nonnull
    protected Map<String, Result> updateParents(ReleaseImpl release, ReleasedVersionable releasedVersionable) throws RepositoryException {
        Map<String, Result> resultMap = new TreeMap<>();

        String[] levels = releasedVersionable.getRelativePath().split("/");
        ResourceHandle inWorkspace = ResourceHandle.use(release.getReleaseRoot());
        ResourceHandle inRelease = ResourceHandle.use(release.getWorkspaceCopyNode());
        Iterator<String> levelIt = IteratorUtils.arrayIterator(levels);
        NodeTreeSynchronizer sync = new NodeTreeSynchronizer();

        while (inWorkspace.isValid() && inRelease.isValid() && !inRelease.isOfType(TYPE_VERSIONREFERENCE)) {
            sync.updateAttributes(inWorkspace, inRelease);
            if (!levelIt.hasNext()) break;
            String level = levelIt.next();
            inWorkspace = ResourceHandle.use(inWorkspace.getChild(level));
            inRelease = ResourceHandle.use(inRelease.getChild(level));
            if (inWorkspace.isValid() && inRelease.isValid()) {
                // we do that for all nodes except the root but including the version reference itself:
                Result result = updateSiblingOrder(inWorkspace, inRelease);
                if (result != Result.unchanged)
                    resultMap.put(inWorkspace.getParent().getPath(), result);
            }
        }

        return resultMap;
    }

    /**
     * Adjusts the place of inRelease wrt. it's siblings to be consistent with inWorkspace.
     *
     * @return true if the ordering was deterministic, false if there was heuristics involved and the user should check the result.
     */
    protected Result updateSiblingOrder(ResourceHandle inWorkspace, ResourceHandle inRelease) throws RepositoryException {
        return getSiblingOrderUpdateStrategy().adjustSiblingOrderOfDestination(inWorkspace, inRelease);
    }

    protected SiblingOrderUpdateStrategy getSiblingOrderUpdateStrategy() {
        return siblingOrderUpdateStrategy;
    }

    /**
     * Removes old nodes that are not version references and have no children. That can happen when a versionreference
     * is moved to another node and there is no version reference left below it's old parent.
     */
    protected void cleanupOrphans(String releaseWorkspaceCopyPath, Resource resource) throws PersistenceException {
        while (resource != null && !resource.getPath().equals(releaseWorkspaceCopyPath)
                && !ResourceHandle.use(resource).isOfType(TYPE_VERSIONREFERENCE) && !resource.hasChildren()) {
            Resource tmp = resource.getParent();
            LOG.info("Deleting obsolete {}", resource.getPath());
            resource.getResourceResolver().delete(resource);
            resource = tmp;
        }
    }

    @Override
    @Nonnull
    public ResourceResolver getResolverForRelease(@Nonnull Release release, @Nullable ReleaseMapper releaseMapper, boolean closeResolverOnClose) {
        return new StagingResourceResolver(release, ReleaseImpl.unwrap(release).getReleaseRoot().getResourceResolver(),
                releaseMapper != null ? releaseMapper : ReleaseMapper.ALLPERMISSIVE, resourceResolverFactory, configuration, closeResolverOnClose);
    }

    /** Ensures the technical resources for a release are there. If the release is created, the root is completely empty. */
    protected ReleaseImpl ensureRelease(@Nonnull Resource theRoot, @Nonnull String releaseLabel) throws RepositoryException, PersistenceException {
        ResourceHandle root = ResourceHandle.use(theRoot);
        if (!root.isValid() && !root.isOfType(TYPE_MIX_RELEASE_ROOT))
            throw new IllegalArgumentException("Not a release root: " + theRoot.getPath());

        ResourceHandle contentnode = ResourceHandle.use(ResourceHandle.use(root.getChild(CONTENT_NODE)));
        if (contentnode.isValid()) { // ensure mixin is there if the node was created otherwise
            SlingResourceUtil.addMixin(contentnode, TYPE_MIX_RELEASE_CONFIG);
        } else {
            contentnode = ResourceHandle.use(root.getResourceResolver().create(root, CONTENT_NODE,
                    ImmutableMap.of(PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                            PROP_MIXINTYPES, TYPE_MIX_RELEASE_CONFIG)));
        }

        Resource currentReleaseNode = ResourceUtil.getOrCreateChild(contentnode, NODE_RELEASES + "/" + releaseLabel, TYPE_UNSTRUCTURED);
        SlingResourceUtil.addMixin(currentReleaseNode, TYPE_REFERENCEABLE);

        Resource releaseWorkspaceCopy = ResourceUtil.getOrCreateChild(currentReleaseNode, NODE_RELEASE_ROOT, TYPE_UNSTRUCTURED);

        ResourceHandle metaData = ResourceHandle.use(currentReleaseNode.getChild(NODE_RELEASE_METADATA));
        if (!metaData.isValid()) {
            metaData = ResourceHandle.use(root.getResourceResolver().create(currentReleaseNode, NODE_RELEASE_METADATA,
                    ImmutableMap.of(PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED,
                            PROP_MIXINTYPES,
                            new String[]{TYPE_CREATED, TYPE_LAST_MODIFIED, TYPE_TITLE})));
        }

        return new ReleaseImpl(root, currentReleaseNode); // incl. validation
    }

    public static class ReleaseImpl implements StagingReleaseManager.Release {

        @Nonnull
        final Resource releaseRoot;

        @Nonnull
        final Resource releaseNode;

        ReleaseImpl(@Nonnull Resource releaseRoot, @Nonnull Resource releaseNode) {
            this.releaseRoot = requireNonNull(releaseRoot);
            this.releaseNode = requireNonNull(releaseNode);
            validate();
        }

        /** A quick sanity check that all needed nodes are there. */
        public void validate() {
            ResourceHandle root = ResourceHandle.use(releaseRoot);
            if (!root.isValid() && !root.isOfType(TYPE_MIX_RELEASE_ROOT))
                throw new IllegalArgumentException("Not a release root: " + releaseRoot.getPath());
            ResourceHandle node = ResourceHandle.use(releaseNode);
            if (!node.getPath().startsWith(root.getPath() + "/") ||
                    !node.getParent().getName().equals(NODE_RELEASES) ||
                    !node.getParent().getParent().getName().equals(CONTENT_NODE) ||
                    !node.getParent().getParent().isOfType(TYPE_MIX_RELEASE_CONFIG) ||
                    !node.getParent(3).getPath().equals(root.getPath()))
                throw new IllegalArgumentException("Suspicious release node in " + this);
            if (node.getChild(NODE_RELEASE_METADATA) == null)
                throw new IllegalArgumentException("No metadata node in " + this);
        }

        @Override
        @Nonnull
        public String getUuid() {
            return ResourceHandle.use(releaseNode).getProperty(PROP_UUID);
        }

        @Override
        @Nonnull
        public String getNumber() {
            return releaseNode.getName();
        }

        @Override
        @Nonnull
        public Resource getReleaseRoot() {
            return releaseRoot;
        }

        @Override
        @Nonnull
        public Resource getMetaDataNode() {
            return requireNonNull(releaseNode.getChild(NODE_RELEASE_METADATA), "No metadata node on " + releaseNode.getPath());
        }

        /**
         * The resource that contains the data for the release - including the subnode {@value StagingConstants#NODE_RELEASE_ROOT}
         * with the copy of the data. Don't touch {@value StagingConstants#NODE_RELEASE_ROOT} - always use the
         * {@link StagingReleaseManager} for that!
         */
        @Nonnull
        public Resource getReleaseNode() {
            return releaseNode;
        }

        /** The node that contains the workspace copy for the release. */
        @Nonnull
        public Resource getWorkspaceCopyNode() {
            return requireNonNull(getReleaseNode().getChild(NODE_RELEASE_ROOT));
        }

        @Override
        public boolean appliesToPath(@Nullable String path) {
            return SlingResourceUtil.isSameOrDescendant(releaseRoot.getPath(), path);
        }

        @Override
        public String toString() {
            return "Release('" + releaseNode.getName() + "'," + releaseRoot.getPath() + ")";
        }

        /**
         * This is used to unwrap release to be able to access implementation specific methods,
         * and performs a sanity check that the release is still there - that's also a weak
         * measure to ensure this was created in the {@link DefaultStagingReleaseManager}, not outside.
         */
        public static ReleaseImpl unwrap(@Nullable Release release) {
            if (release != null) ((ReleaseImpl) release).validate();
            return (ReleaseImpl) release;
        }

        protected void updateLastModified() throws RepositoryException {
            ResourceHandle metaData = ResourceHandle.use(getMetaDataNode());
            if (metaData.isOfType(TYPE_LAST_MODIFIED)) {
                metaData.setProperty(JCR_LASTMODIFIED, Calendar.getInstance());
                metaData.setProperty(CoreConstants.JCR_LASTMODIFIED_BY, getReleaseRoot().getResourceResolver().getUserID());
            }
        }

        /** Maps paths pointing into the release content to the release content copy. */
        @Nonnull
        public String mapToContentCopy(@Nonnull String path) {
            if (appliesToPath(path)) {
                path = ResourceUtil.normalize(path);
                if (releaseRoot.getPath().equals(path))
                    path = getWorkspaceCopyNode().getPath();
                else if (null != path) {
                    assert path.startsWith(releaseRoot.getPath());
                    path = getWorkspaceCopyNode().getPath() + '/'
                            + path.substring(releaseRoot.getPath().length() + 1);
                }
            }
            return path;
        }
    }

    @ObjectClassDefinition(
            name = "Composum Platform Staging Release Manager Configuration"
    )
    public @interface Configuration {

        @AttributeDefinition(
                name = "Overlayed Nodes",
                description = "Some nodes that are overlayed from the top level of the working content into the release"
        )
        String[] overlayed_nodes() default {CONTENT_NODE, "assets"};

        @AttributeDefinition(
                name = "Removed Paths",
                description = "Some paths that are removed frm the overlayed nodes (relative to the release top level)"
        )
        String[] removed_paths() default {CONTENT_NODE + '/' + NODE_RELEASES};
    }

}