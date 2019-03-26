package com.composum.sling.platform.staging.service;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.util.ResourceUtil;
import com.composum.sling.platform.staging.StagingConstants;
import com.composum.sling.platform.staging.impl.ReleaseTreeSynchronizer;
import com.google.common.collect.ImmutableMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceNotFoundException;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
public class DefaultStagingReleaseManager implements StagingReleaseManager, StagingConstants {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultStagingReleaseManager.class);

    /** Sub-path from the release root to the releases node. */
    static final String RELPATH_RELEASES_NODE = ResourceUtil.CONTENT_NODE + "/" + NODE_RELEASES;

    /** Sub-path from the release root to the releases node. */
    static final String RELPATH_CURRENTRELEASE_NODE = ResourceUtil.CONTENT_NODE + "/" + NODE_RELEASES;

    @Nullable
    @Override
    public ResourceHandle findReleaseRoot(@Nonnull Resource resource) {
        Resource result = resource;
        while (result != null && !ResourceHandle.use(result).isOfType(TYPE_MIX_RELEASE_ROOT))
            result = result.getParent();
        return result != null ? ResourceHandle.use(resource) : null;
    }

    @Nonnull
    @Override
    public List<Release> getReleases(@Nonnull Resource resource) {
        List<Release> result = new ArrayList<>();
        ResourceHandle root = ResourceHandle.use(findReleaseRoot(resource));
        if (root.isValid()) {
            Resource releasesNode = root.getChild(RELPATH_RELEASES_NODE);
            if (releasesNode != null) {
                for (Resource releaseNode : releasesNode.getChildren())
                    result.add(new ReleaseImpl(root, releaseNode));
            }
        }
        return result;
    }

    @Nullable
    Release getAndUpdateCurrentRelease(@Nonnull Resource resource) throws PersistenceException, RepositoryException {
        ResourceHandle root = ResourceHandle.use(findReleaseRoot(resource));
        Release release = null;
        if (root.isValid()) {
            updateCurrentReleaseFromWorkspace(root);
            release = new ReleaseImpl(root, root.getChild(RELPATH_CURRENTRELEASE_NODE));
        }
        return release;
    }

    @Override
    public void updateCurrentReleaseFromWorkspace(@Nonnull Resource resource) throws ResourceNotFoundException, PersistenceException, RepositoryException {
        ResourceHandle root = ResourceHandle.use(findReleaseRoot(resource));
        if (root.isValid()) {
            ResourceHandle contentnode = ResourceHandle.use(ResourceHandle.use(root.getChild(ResourceUtil.CONTENT_NODE)));
            if (!contentnode.isValid()) {
                contentnode = ResourceHandle.use(resource.getResourceResolver().create(root, ResourceUtil.CONTENT_NODE,
                        ImmutableMap.of(ResourceUtil.PROP_PRIMARY_TYPE, ResourceUtil.TYPE_UNSTRUCTURED,
                                ResourceUtil.PROP_MIXINTYPES, TYPE_MIX_RELEASE_CONFIG)));
            }
            ResourceHandle currentReleaseNode = ResourceHandle.use(ResourceUtil.getOrCreateChild(contentnode, NODE_RELEASES + "/" + NODE_CURRENT_RELEASE, ResourceUtil.TYPE_UNSTRUCTURED));
            ResourceHandle releaseWorkspaceCopy = ResourceHandle.use(ResourceUtil.getOrCreateChild(currentReleaseNode, NODE_RELEASE_ROOT, ResourceUtil.TYPE_UNSTRUCTURED));
            ResourceHandle metaData = ResourceHandle.use(currentReleaseNode.getChild(NODE_RELEASE_METADATA));
            if (metaData.isValid()) {
                metaData = ResourceHandle.use(resource.getResourceResolver().create(root, ResourceUtil.CONTENT_NODE,
                        ImmutableMap.of(ResourceUtil.PROP_PRIMARY_TYPE, ResourceUtil.TYPE_UNSTRUCTURED,
                                ResourceUtil.PROP_MIXINTYPES,
                                new String[]{ResourceUtil.TYPE_CREATED, ResourceUtil.PROP_LAST_MODIFIED, ResourceUtil.TYPE_TITLE})));
            }
            // now that everything was already there or is created, we update the copy from the working tree.
            new ReleaseTreeSynchronizer().update(root, releaseWorkspaceCopy);
        } else throw new ResourceNotFoundException("No release root found for " + ResourceUtil.getPath(resource));
    }

    @Nullable
    @Override
    public ResourceResolver resolverForRelease(@Nonnull Resource releaseRoot, @Nonnull String releaseLabel) {
        LOG.error("DefaultStagingReleaseManager.resolverForRelease");
        if (0 == 0)
            throw new UnsupportedOperationException("Not implemented yet: DefaultStagingReleaseManager.resolverForRelease");
        // FIXME hps 2019-03-26 implement DefaultStagingReleaseManager.resolverForRelease
        ResourceResolver result = null;
        return result;
    }

    static class ReleaseImpl implements StagingReleaseManager.Release {

        @Nonnull
        final Resource releaseRoot;
        @Nonnull
        final Resource releaseNode;

        ReleaseImpl(@Nonnull Resource releaseRoot, @Nonnull Resource releaseNode) {
            this.releaseRoot = Objects.requireNonNull(releaseRoot);
            this.releaseNode = Objects.requireNonNull(releaseNode);
        }

        @Override
        public String getLabel() {
            return releaseNode.getName();
        }

        @Override
        public Resource getReleaseRoot() {
            return releaseRoot;
        }

        @Override
        public Resource getMetaDataNode() {
            return Objects.requireNonNull(releaseNode.getChild(NODE_RELEASE_METADATA), "No metadata node on " + releaseNode.getPath());
        }

        @Override
        public Resource getReleaseNode() {
            return releaseNode;
        }
    }
}
