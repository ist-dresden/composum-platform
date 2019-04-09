package com.composum.sling.platform.staging.service;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.StagingResourceResolverImpl;
import com.composum.sling.platform.staging.impl.NodeTreeSynchronizer;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy;
import com.composum.sling.platform.staging.impl.SiblingOrderUpdateStrategy.Result;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Default implementation of {@link StagingReleaseManager} - description see there.
 *
 * @see StagingReleaseManager
 */
@Component(
        service = {StagingReleaseManager.class},
        property = {
                Constants.SERVICE_DESCRIPTION + "=Staging Release Manager"
        }
)
public class DefaultStagingReleaseManager implements StagingReleaseManager {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultStagingReleaseManager.class);

    /** Sub-path from the release root to the releases node. */
    static final String RELPATH_RELEASES_NODE = ResourceUtil.CONTENT_NODE + '/' + NODE_RELEASES;

    private SiblingOrderUpdateStrategy siblingOrderUpdateStrategy = new SiblingOrderUpdateStrategy();

    @Reference
    protected ResourceResolverFactory resourceResolverFactory;

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
                    ResourceUtil.TYPE_UNSTRUCTURED + '/' + TYPE_VERSIONREFERENCE);
        } else if (!versionReference.getPath().equals(newPath)) {
            Resource oldParent = versionReference.getParent();
            ResourceResolver resolver = versionReference.getResourceResolver();

            ResourceUtil.getOrCreateResource(resolver, ResourceUtil.getParent(newPath), ResourceUtil.TYPE_UNSTRUCTURED);
            resolver.move(versionReference.getPath(), ResourceUtil.getParent(newPath));
            versionReference = resolver.getResource(newPath);

            cleanupOrphans(releaseWorkspaceCopy.getPath(), oldParent);
        }

        releasedVersionable.writeToVersionReference(requireNonNull(versionReference));
        // FIXME hps 2019-04-05 handle ordering and super attributes; and removal of obsolete nodes.

        return updateParents(release, releasedVersionable);
    }

    /**
     * Goes through all parents of the version reference, sets their attributes from the working copy
     * and fixes the node ordering if necessary.
     * @return a map with paths where we changed the order of children in the release.
     */
    @Nonnull
    protected Map<String, Result> updateParents(ReleaseImpl release, ReleasedVersionable releasedVersionable) throws RepositoryException {
        Map<String, Result> resultMap = new LinkedHashMap<>();

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
            resource.getResourceResolver().delete(resource);
            resource = tmp;
        }
    }

    @Override
    @Nonnull
    public ResourceResolver getResolverForRelease(@Nonnull Release release, @Nullable ReleaseMapper releaseMapper) {
        return new StagingResourceResolverImpl(release, ReleaseImpl.unwrap(release).getReleaseRoot().getResourceResolver(),
                releaseMapper != null ? releaseMapper : ReleaseMapper.ALLPERMISSIVE, resourceResolverFactory);
    }

    /** Ensures the technical resources for a release are there. If the release is created, the root is completely empty. */
    protected ReleaseImpl ensureRelease(@Nonnull Resource theRoot, @Nonnull String releaseLabel) throws RepositoryException, PersistenceException {
        ResourceHandle root = ResourceHandle.use(theRoot);
        if (!root.isValid() && !root.isOfType(TYPE_MIX_RELEASE_ROOT))
            throw new IllegalArgumentException("Not a release root: " + theRoot.getPath());

        ResourceHandle contentnode = ResourceHandle.use(ResourceHandle.use(root.getChild(ResourceUtil.CONTENT_NODE)));
        if (contentnode.isValid()) { // ensure mixin is there if the node was created otherwise
            ResourceUtil.addMixin(contentnode, TYPE_MIX_RELEASE_CONFIG);
        } else {
            contentnode = ResourceHandle.use(root.getResourceResolver().create(root, ResourceUtil.CONTENT_NODE,
                    ImmutableMap.of(ResourceUtil.PROP_PRIMARY_TYPE, ResourceUtil.TYPE_UNSTRUCTURED,
                            ResourceUtil.PROP_MIXINTYPES, TYPE_MIX_RELEASE_CONFIG)));
        }

        Resource currentReleaseNode = ResourceUtil.getOrCreateChild(contentnode, NODE_RELEASES + "/" + releaseLabel, ResourceUtil.TYPE_UNSTRUCTURED);
        ResourceUtil.addMixin(currentReleaseNode, ResourceUtil.TYPE_REFERENCEABLE);

        Resource releaseWorkspaceCopy = ResourceUtil.getOrCreateChild(currentReleaseNode, NODE_RELEASE_ROOT, ResourceUtil.TYPE_UNSTRUCTURED);

        ResourceHandle metaData = ResourceHandle.use(currentReleaseNode.getChild(NODE_RELEASE_METADATA));
        if (!metaData.isValid()) {
            metaData = ResourceHandle.use(root.getResourceResolver().create(currentReleaseNode, NODE_RELEASE_METADATA,
                    ImmutableMap.of(ResourceUtil.PROP_PRIMARY_TYPE, ResourceUtil.TYPE_UNSTRUCTURED,
                            ResourceUtil.PROP_MIXINTYPES,
                            new String[]{ResourceUtil.TYPE_CREATED, ResourceUtil.TYPE_LAST_MODIFIED, ResourceUtil.TYPE_TITLE})));
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
                    !node.getParent().getParent().getName().equals(ResourceUtil.CONTENT_NODE) ||
                    !node.getParent().getParent().isOfType(TYPE_MIX_RELEASE_CONFIG) ||
                    !node.getParent(3).getPath().equals(root.getPath()))
                throw new IllegalArgumentException("Suspicious release node in " + this);
            if (node.getChild(NODE_RELEASE_METADATA) == null)
                throw new IllegalArgumentException("No metadata node in " + this);
        }

        @Override
        @Nonnull
        public String getUuid() {
            return ResourceHandle.use(releaseNode).getProperty(ResourceUtil.PROP_UUID);
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
            if (path == null) return false;
            String normalized = ResourceUtil.normalize(path);
            return releaseRoot.getPath().equals(normalized) || StringUtils.startsWith(normalized, releaseRoot.getPath() + "/");
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
        @Nullable
        public static ReleaseImpl unwrap(@Nullable Release release) {
            if (release != null) ((ReleaseImpl) release).validate();
            return (ReleaseImpl) release;
        }
    }
}
